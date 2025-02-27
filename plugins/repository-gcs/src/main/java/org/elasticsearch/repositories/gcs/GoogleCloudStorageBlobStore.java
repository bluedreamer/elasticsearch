/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.repositories.gcs;

import com.google.api.gax.paging.Page;
import com.google.cloud.BatchResult;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageBatch;
import com.google.cloud.storage.StorageException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobMetadata;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.blobstore.DeleteResult;
import org.elasticsearch.common.blobstore.support.PlainBlobMetadata;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.hash.MessageDigests;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;

import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_PRECON_FAILED;

class GoogleCloudStorageBlobStore implements BlobStore {

    private static final Logger logger = LogManager.getLogger(GoogleCloudStorageBlobStore.class);

    // The recommended maximum size of a blob that should be uploaded in a single
    // request. Larger files should be uploaded over multiple requests (this is
    // called "resumable upload")
    // https://cloud.google.com/storage/docs/json_api/v1/how-tos/resumable-upload
    public static final int LARGE_BLOB_THRESHOLD_BYTE_SIZE;

    static {
        final String key = "es.repository_gcs.large_blob_threshold_byte_size";
        final String largeBlobThresholdByteSizeProperty = System.getProperty(key);
        if (largeBlobThresholdByteSizeProperty == null) {
            LARGE_BLOB_THRESHOLD_BYTE_SIZE = Math.toIntExact(new ByteSizeValue(5, ByteSizeUnit.MB).getBytes());
        } else {
            final int largeBlobThresholdByteSize;
            try {
              largeBlobThresholdByteSize = Integer.parseInt(largeBlobThresholdByteSizeProperty);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException("failed to parse " + key + " having value [" + largeBlobThresholdByteSizeProperty + "]");
            }
            if (largeBlobThresholdByteSize <= 0) {
                throw new IllegalArgumentException(key + " must be positive but was [" + largeBlobThresholdByteSizeProperty + "]");
            }
            LARGE_BLOB_THRESHOLD_BYTE_SIZE = largeBlobThresholdByteSize;
        }
    }

    private final String bucketName;
    private final String clientName;
    private final String repositoryName;
    private final GoogleCloudStorageService storageService;
    private final GoogleCloudStorageOperationsStats stats;
    private final int bufferSize;

    GoogleCloudStorageBlobStore(String bucketName,
                                String clientName,
                                String repositoryName,
                                GoogleCloudStorageService storageService,
                                int bufferSize) {
        this.bucketName = bucketName;
        this.clientName = clientName;
        this.repositoryName = repositoryName;
        this.storageService = storageService;
        this.stats = new GoogleCloudStorageOperationsStats(bucketName);
        this.bufferSize = bufferSize;
    }

    private Storage client() throws IOException {
        return storageService.client(clientName, repositoryName, stats);
    }

    @Override
    public BlobContainer blobContainer(BlobPath path) {
        return new GoogleCloudStorageBlobContainer(path, this);
    }

    @Override
    public void close() {
        storageService.closeRepositoryClient(repositoryName);
    }

    /**
     * List blobs in the specific bucket under the specified path. The path root is removed.
     *
     * @param path base path of the blobs to list
     * @return a map of blob names and their metadata
     */
    Map<String, BlobMetadata> listBlobs(String path) throws IOException {
        return listBlobsByPrefix(path, "");
    }

    /**
     * List all blobs in the specific bucket with names prefixed
     *
     * @param path
     *            base path of the blobs to list. This path is removed from the
     *            names of the blobs returned.
     * @param prefix prefix of the blobs to list.
     * @return a map of blob names and their metadata.
     */
    Map<String, BlobMetadata> listBlobsByPrefix(String path, String prefix) throws IOException {
        final String pathPrefix = buildKey(path, prefix);
        final MapBuilder<String, BlobMetadata> mapBuilder = MapBuilder.newMapBuilder();
        SocketAccess.doPrivilegedVoidIOException(
            () -> client().list(bucketName, BlobListOption.currentDirectory(), BlobListOption.prefix(pathPrefix)).iterateAll().forEach(
                blob -> {
                    assert blob.getName().startsWith(path);
                    if (blob.isDirectory() == false) {
                        final String suffixName = blob.getName().substring(path.length());
                        mapBuilder.put(suffixName, new PlainBlobMetadata(suffixName, blob.getSize()));
                    }
                }));
        return mapBuilder.immutableMap();
    }

    Map<String, BlobContainer> listChildren(BlobPath path) throws IOException {
        final String pathStr = path.buildAsString();
        final MapBuilder<String, BlobContainer> mapBuilder = MapBuilder.newMapBuilder();
        SocketAccess.doPrivilegedVoidIOException
            (() -> client().list(bucketName, BlobListOption.currentDirectory(), BlobListOption.prefix(pathStr)).iterateAll().forEach(
                blob -> {
                    if (blob.isDirectory()) {
                        assert blob.getName().startsWith(pathStr);
                        assert blob.getName().endsWith("/");
                        // Strip path prefix and trailing slash
                        final String suffixName = blob.getName().substring(pathStr.length(), blob.getName().length() - 1);
                        if (suffixName.isEmpty() == false) {
                            mapBuilder.put(suffixName, new GoogleCloudStorageBlobContainer(path.add(suffixName), this));
                        }
                    }
                }));
        return mapBuilder.immutableMap();
    }

    /**
     * Returns true if the blob exists in the specific bucket
     *
     * @param blobName name of the blob
     * @return true iff the blob exists
     */
    boolean blobExists(String blobName) throws IOException {
        final BlobId blobId = BlobId.of(bucketName, blobName);
        final Blob blob = SocketAccess.doPrivilegedIOException(() -> client().get(blobId));
        return blob != null;
    }

    /**
     * Returns an {@link java.io.InputStream} for the given blob name
     *
     * @param blobName name of the blob
     * @return the InputStream used to read the blob's content
     */
    InputStream readBlob(String blobName) throws IOException {
        return new GoogleCloudStorageRetryingInputStream(client(), BlobId.of(bucketName, blobName));
    }

    /**
     * Returns an {@link java.io.InputStream} for the given blob's position and length
     *
     * @param blobName name of the blob
     * @param position starting position to read from
     * @param length length of bytes to read
     * @return the InputStream used to read the blob's content
     */
    InputStream readBlob(String blobName, long position, long length) throws IOException {
        if (position < 0L) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        if (length == 0) {
            return new ByteArrayInputStream(new byte[0]);
        } else {
            return new GoogleCloudStorageRetryingInputStream(client(), BlobId.of(bucketName, blobName), position,
                Math.addExact(position, length - 1));
        }
    }

    /**
     * Writes a blob in the specific bucket
     * @param bytes       content of the blob to be written
     * @param failIfAlreadyExists whether to throw a FileAlreadyExistsException if the given blob already exists
     */
    void writeBlob(String blobName, BytesReference bytes, boolean failIfAlreadyExists) throws IOException {
        if (bytes.length() > getLargeBlobThresholdInBytes()) {
            // Compute md5 here so #writeBlobResumable forces the integrity check on the resumable upload.
            // This is needed since we rely on atomic write behavior when writing BytesReferences in BlobStoreRepository which is not
            // guaranteed for resumable uploads.
            final String md5 = Base64.getEncoder().encodeToString(MessageDigests.digest(bytes, MessageDigests.md5()));
            writeBlobResumable(BlobInfo.newBuilder(bucketName, blobName).setMd5(md5).build(), bytes.streamInput(), bytes.length(),
                failIfAlreadyExists);
        } else {
            writeBlob(bytes.streamInput(), bytes.length(), failIfAlreadyExists, BlobInfo.newBuilder(bucketName, blobName).build());
        }
    }

    /**
     * Writes a blob in the specific bucket
     * @param inputStream content of the blob to be written
     * @param blobSize    expected size of the blob to be written
     * @param failIfAlreadyExists whether to throw a FileAlreadyExistsException if the given blob already exists
     */
    void writeBlob(String blobName, InputStream inputStream, long blobSize, boolean failIfAlreadyExists) throws IOException {
        writeBlob(inputStream, blobSize, failIfAlreadyExists, BlobInfo.newBuilder(bucketName, blobName).build());
    }

    private void writeBlob(InputStream inputStream, long blobSize, boolean failIfAlreadyExists, BlobInfo blobInfo) throws IOException {
        if (blobSize > getLargeBlobThresholdInBytes()) {
            writeBlobResumable(blobInfo, inputStream, blobSize, failIfAlreadyExists);
        } else {
            writeBlobMultipart(blobInfo, inputStream, blobSize, failIfAlreadyExists);
        }
    }

    // non-static, package private for testing
    long getLargeBlobThresholdInBytes() {
        return LARGE_BLOB_THRESHOLD_BYTE_SIZE;
    }

    // possible options for #writeBlobResumable uploads
    private static final Storage.BlobWriteOption[] NO_OVERWRITE_NO_MD5 = {Storage.BlobWriteOption.doesNotExist()};
    private static final Storage.BlobWriteOption[] OVERWRITE_NO_MD5 = new Storage.BlobWriteOption[0];
    private static final Storage.BlobWriteOption[] NO_OVERWRITE_CHECK_MD5 =
            {Storage.BlobWriteOption.doesNotExist(), Storage.BlobWriteOption.md5Match()};
    private static final Storage.BlobWriteOption[] OVERWRITE_CHECK_MD5 = {Storage.BlobWriteOption.md5Match()};

    void writeBlob(String blobName, boolean failIfAlreadyExists, CheckedConsumer<OutputStream, IOException> writer) throws IOException {
        final BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, blobName).build();
        final Storage.BlobWriteOption[] writeOptions = failIfAlreadyExists ? NO_OVERWRITE_NO_MD5 : OVERWRITE_NO_MD5;

        StorageException storageException = null;

        for (int retry = 0; retry < 3; ++retry) {
            try {
                final WriteChannel writeChannel = SocketAccess.doPrivilegedIOException(() -> client().writer(blobInfo, writeOptions));
                writer.accept(new FilterOutputStream(Channels.newOutputStream(new WritableBlobChannel(writeChannel))) {
                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        int written = 0;
                        while (written < len) {
                            // at most write the default chunk size in one go to prevent allocating huge buffers in the SDK
                            // see com.google.cloud.BaseWriteChannel#DEFAULT_CHUNK_SIZE
                            final int toWrite = Math.min(len - written, 60 * 256 * 1024);
                            out.write(b, off + written, toWrite);
                            written += toWrite;
                        }
                    }
                });
                SocketAccess.doPrivilegedVoidIOException(writeChannel::close);
                stats.trackPutOperation();
                return;
            } catch (final StorageException se) {
                final int errorCode = se.getCode();
                if (errorCode == HTTP_GONE) {
                    logger.warn(() -> new ParameterizedMessage("Retrying broken resumable upload session for blob {}", blobInfo), se);
                    storageException = ExceptionsHelper.useOrSuppress(storageException, se);
                    continue;
                } else if (failIfAlreadyExists && errorCode == HTTP_PRECON_FAILED) {
                    throw new FileAlreadyExistsException(blobInfo.getBlobId().getName(), null, se.getMessage());
                }
                if (storageException != null) {
                    se.addSuppressed(storageException);
                }
                throw se;
            }
        }
        assert storageException != null;
        throw storageException;
    }

    /**
     * Uploads a blob using the "resumable upload" method (multiple requests, which
     * can be independently retried in case of failure, see
     * https://cloud.google.com/storage/docs/json_api/v1/how-tos/resumable-upload
     * @param blobInfo the info for the blob to be uploaded
     * @param inputStream the stream containing the blob data
     * @param size expected size of the blob to be written
     * @param failIfAlreadyExists whether to throw a FileAlreadyExistsException if the given blob already exists
     */
    private void writeBlobResumable(BlobInfo blobInfo, InputStream inputStream, long size, boolean failIfAlreadyExists)
            throws IOException {
        // We retry 410 GONE errors to cover the unlikely but possible scenario where a resumable upload session becomes broken and
        // needs to be restarted from scratch. Given how unlikely a 410 error should be according to SLAs we retry only twice.
        assert inputStream.markSupported();
        inputStream.mark(Integer.MAX_VALUE);
        final byte[] buffer = new byte[size < bufferSize ? Math.toIntExact(size) : bufferSize];
        StorageException storageException = null;
        final Storage.BlobWriteOption[] writeOptions;
        if (blobInfo.getMd5() == null) {
            // no md5, use options without checksum validation
            writeOptions = failIfAlreadyExists ? NO_OVERWRITE_NO_MD5 : OVERWRITE_NO_MD5;
        } else {
            // md5 value is set so we use it by enabling checksum validation
            writeOptions = failIfAlreadyExists ? NO_OVERWRITE_CHECK_MD5 : OVERWRITE_CHECK_MD5;
        }
        for (int retry = 0; retry < 3; ++retry) {
            try {
                final WriteChannel writeChannel = SocketAccess.doPrivilegedIOException(() -> client().writer(blobInfo, writeOptions));
                /*
                 * It is not enough to wrap the call to Streams#copy, we have to wrap the privileged calls too; this is because Streams#copy
                 * is in the stacktrace and is not granted the permissions needed to close and write the channel.
                 */
                org.elasticsearch.core.internal.io.Streams.copy(
                        inputStream, Channels.newOutputStream(new WritableBlobChannel(writeChannel)), buffer);
                SocketAccess.doPrivilegedVoidIOException(writeChannel::close);
                // We don't track this operation on the http layer as
                // we do with the GET/LIST operations since this operations
                // can trigger multiple underlying http requests but only one
                // operation is billed.
                stats.trackPutOperation();
                return;
            } catch (final StorageException se) {
                final int errorCode = se.getCode();
                if (errorCode == HTTP_GONE) {
                    logger.warn(() -> new ParameterizedMessage("Retrying broken resumable upload session for blob {}", blobInfo), se);
                    storageException = ExceptionsHelper.useOrSuppress(storageException, se);
                    inputStream.reset();
                    continue;
                } else if (failIfAlreadyExists && errorCode == HTTP_PRECON_FAILED) {
                    throw new FileAlreadyExistsException(blobInfo.getBlobId().getName(), null, se.getMessage());
                }
                if (storageException != null) {
                    se.addSuppressed(storageException);
                }
                throw se;
            }
        }
        assert storageException != null;
        throw storageException;
    }

    /**
     * Uploads a blob using the "multipart upload" method (a single
     * 'multipart/related' request containing both data and metadata. The request is
     * gziped), see:
     * https://cloud.google.com/storage/docs/json_api/v1/how-tos/multipart-upload
     *  @param blobInfo the info for the blob to be uploaded
     * @param inputStream the stream containing the blob data
     * @param blobSize the size
     * @param failIfAlreadyExists whether to throw a FileAlreadyExistsException if the given blob already exists
     */
    private void writeBlobMultipart(BlobInfo blobInfo, InputStream inputStream, long blobSize, boolean failIfAlreadyExists)
        throws IOException {
        assert blobSize <= getLargeBlobThresholdInBytes() : "large blob uploads should use the resumable upload method";
        final byte[] buffer = new byte[Math.toIntExact(blobSize)];
        Streams.readFully(inputStream, buffer);
        try {
            final Storage.BlobTargetOption[] targetOptions = failIfAlreadyExists ?
                new Storage.BlobTargetOption[] { Storage.BlobTargetOption.doesNotExist() } :
                new Storage.BlobTargetOption[0];
            SocketAccess.doPrivilegedVoidIOException(
                    () -> client().create(blobInfo, buffer, targetOptions));
            // We don't track this operation on the http layer as
            // we do with the GET/LIST operations since this operations
            // can trigger multiple underlying http requests but only one
            // operation is billed.
            stats.trackPostOperation();
        } catch (final StorageException se) {
            if (failIfAlreadyExists && se.getCode() == HTTP_PRECON_FAILED) {
                throw new FileAlreadyExistsException(blobInfo.getBlobId().getName(), null, se.getMessage());
            }
            throw se;
        }
    }

    /**
     * Deletes the given path and all its children.
     *
     * @param pathStr Name of path to delete
     */
    DeleteResult deleteDirectory(String pathStr) throws IOException {
        return SocketAccess.doPrivilegedIOException(() -> {
            DeleteResult deleteResult = DeleteResult.ZERO;
            Page<Blob> page = client().list(bucketName, BlobListOption.prefix(pathStr));
            do {
                final AtomicLong blobsDeleted = new AtomicLong(0L);
                final AtomicLong bytesDeleted = new AtomicLong(0L);
                final Iterator<Blob> blobs = page.getValues().iterator();
                deleteBlobsIgnoringIfNotExists(new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return blobs.hasNext();
                    }

                    @Override
                    public String next() {
                        final Blob next = blobs.next();
                        blobsDeleted.incrementAndGet();
                        bytesDeleted.addAndGet(next.getSize());
                        return next.getName();
                    }
                });
                deleteResult = deleteResult.add(blobsDeleted.get(), bytesDeleted.get());
                page = page.getNextPage();
            } while (page != null);
            return deleteResult;
        });
    }

    /**
     * Deletes multiple blobs from the specific bucket using a batch request
     *
     * @param blobNames names of the blobs to delete
     */
    void deleteBlobsIgnoringIfNotExists(Iterator<String> blobNames) throws IOException {
        if (blobNames.hasNext() == false) {
            return;
        }
        final Iterator<BlobId> blobIdsToDelete = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return blobNames.hasNext();
            }

            @Override
            public BlobId next() {
                return BlobId.of(bucketName, blobNames.next());
            }
        };
        final List<BlobId> failedBlobs = Collections.synchronizedList(new ArrayList<>());
        try {
            SocketAccess.doPrivilegedVoidIOException(() -> {
                final AtomicReference<StorageException> ioe = new AtomicReference<>();
                final StorageBatch batch = client().batch();
                while (blobIdsToDelete.hasNext()) {
                    BlobId blob = blobIdsToDelete.next();
                    batch.delete(blob).notify(
                        new BatchResult.Callback<>() {
                            @Override
                            public void success(Boolean result) {
                            }

                            @Override
                            public void error(StorageException exception) {
                                if (exception.getCode() != HTTP_NOT_FOUND) {
                                    // track up to 10 failed blob deletions for the exception message below
                                    if (failedBlobs.size() < 10) {
                                        failedBlobs.add(blob);
                                    }
                                    if (ioe.compareAndSet(null, exception) == false) {
                                        ioe.get().addSuppressed(exception);
                                    }
                                }
                            }
                        });
                }
                batch.submit();

                final StorageException exception = ioe.get();
                if (exception != null) {
                    throw exception;
                }
            });
        } catch (final Exception e) {
            throw new IOException("Exception when deleting blobs " + failedBlobs, e);
        }
        assert failedBlobs.isEmpty();
    }

    private static String buildKey(String keyPath, String s) {
        assert s != null;
        return keyPath + s;
    }

    @Override
    public Map<String, Long> stats() {
        return stats.toMap();
    }

    private static final class WritableBlobChannel implements WritableByteChannel {

        private final WriteChannel channel;

        WritableBlobChannel(WriteChannel writeChannel) {
            this.channel = writeChannel;
        }

        @SuppressForbidden(reason = "channel is based on a socket")
        @Override
        public int write(final ByteBuffer src) throws IOException {
            return SocketAccess.doPrivilegedIOException(() -> channel.write(src));
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void close() {
            // we manually close the channel later to have control over whether or not we want to finalize a blob
        }
    }
}
