/*
 * Copyright 2011-2014 Amazon Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.services.s3.transfer.internal;

import static com.amazonaws.event.SDKProgressPublisher.publishProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonClientException;
import com.amazonaws.event.ProgressEventType;
import com.amazonaws.event.ProgressListenerChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.transfer.PauseResult;
import com.amazonaws.services.s3.transfer.PauseStatus;
import com.amazonaws.services.s3.transfer.PersistableUpload;
import com.amazonaws.services.s3.transfer.Transfer.TransferState;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.model.UploadResult;

/**
 * Manages an upload by periodically checking to see if the upload is done, and
 * returning a result if so. Otherwise, schedules a copy of itself to be run in
 * the future and returns null. When waiting on the result of this class via a
 * Future object, clients must call {@link UploadMonitor#isDone()} and
 * {@link UploadMonitor#getFuture()}
 */
public class UploadMonitor implements Callable<UploadResult>, TransferMonitor {


    private final AmazonS3 s3;
    private final ExecutorService threadPool;
    private final PutObjectRequest putObjectRequest;
    private ScheduledExecutorService timedThreadPool;

    private final ProgressListenerChain listener;
    private final UploadCallable multipartUploadCallable;
    private final UploadImpl transfer;

    /*
     * State for tracking the upload's progress
     */
    private String uploadId;
    private final List<Future<PartETag>> futures = new ArrayList<Future<PartETag>>();

    /*
     * State for clients wishing to poll for completion
     */
    private boolean isUploadDone = false;
    private Future<UploadResult> nextFuture;

    public synchronized Future<UploadResult> getFuture() {
        return nextFuture;
    }

    private synchronized void setNextFuture(Future<UploadResult> nextFuture) {
        this.nextFuture = nextFuture;
    }

    public synchronized boolean isDone() {
        return isUploadDone;
    }

    private synchronized void markAllDone() {
        isUploadDone = true;
    }

    // TODO: this could be configured in the configuration object (which we're
    // not using right now)
    private int pollInterval = 5000;

    /**
     * Constructs a new upload watcher, which immediately submits itself to the
     * thread pool.
     *
     * @param manager
     *            The {@link TransferManager} that owns this upload.
     * @param transfer
     *            The transfer being processed.
     * @param threadPool
     *            The {@link ExecutorService} to which we should submit new
     *            tasks.
     * @param multipartUploadCallable
     *            The callable responsible for processing the upload asynchronously
     * @param putObjectRequest
     *            The original putObject request
     * @param progressListenerChain
     *            A chain of listeners that wish to be notified of upload
     *            progress
     */
    public UploadMonitor(TransferManager manager, UploadImpl transfer, ExecutorService threadPool,
            UploadCallable multipartUploadCallable, PutObjectRequest putObjectRequest,
            ProgressListenerChain progressListenerChain) {

        this.s3 = manager.getAmazonS3Client();
        this.multipartUploadCallable = multipartUploadCallable;
        this.threadPool = threadPool;
        this.putObjectRequest = putObjectRequest;
        this.listener = progressListenerChain;
        this.transfer = transfer;
        setNextFuture(threadPool.submit(this));
    }

    public void setTimedThreadPool(ScheduledExecutorService timedThreadPool) {
        this.timedThreadPool = timedThreadPool;
    }

    @Override
    public UploadResult call() throws Exception {
        try {
            if ( uploadId == null ) {
                return upload();
            } else {
                return poll();
            }
        } catch ( CancellationException e ) {
            transfer.setState(TransferState.Canceled);
            publishProgress(listener, ProgressEventType.TRANSFER_CANCELED_EVENT);
            throw new AmazonClientException("Upload canceled");
        } catch ( Exception e ) {
            transfer.setState(TransferState.Failed);
            publishProgress(listener, ProgressEventType.TRANSFER_FAILED_EVENT);
            throw e;
        }
    }

    /**
     * Polls for a result from a multipart upload and either returns it if
     * complete, or reschedules to poll again later if not.
     */
    private UploadResult poll() throws InterruptedException {
        for ( Future<PartETag> f : futures ) {
            if ( !f.isDone() ) {
                reschedule();
                return null;
            }
        }

        for ( Future<PartETag> f : futures ) {
            if ( f.isCancelled() ) {
                throw new CancellationException();
            }
        }

        return completeMultipartUpload();
    }

    /**
     * Initiates the upload and checks on the result. If it has completed,
     * returns the result; otherwise, reschedules to check back later.
     */
    private UploadResult upload() throws Exception, InterruptedException {

        UploadResult result = multipartUploadCallable.call();

        if ( result != null ) {
            uploadComplete();
        } else {
            uploadId = multipartUploadCallable.getMultipartUploadId();
            futures.addAll(multipartUploadCallable.getFutures());
            reschedule();
        }

        return result;
    }

    private void uploadComplete() {
        markAllDone();
        transfer.setState(TransferState.Completed);

        // AmazonS3Client takes care of all the events for single part uploads,
        // so we only need to send a completed event for multipart uploads.
        if (multipartUploadCallable.isMultipartUpload()) {
            publishProgress(listener, ProgressEventType.TRANSFER_COMPLETED_EVENT);
        }
    }

    private void reschedule()  {
        setNextFuture(timedThreadPool.schedule(new Callable<UploadResult>() {
            public UploadResult call() throws Exception {
                setNextFuture(threadPool.submit(UploadMonitor.this));
                return null;
            }
        }, pollInterval, TimeUnit.MILLISECONDS));
    }

    /**
     * Completes the multipart upload and returns the result.
     */
    private UploadResult completeMultipartUpload() {
        CompleteMultipartUploadResult completeMultipartUploadResult = s3
                .completeMultipartUpload(new CompleteMultipartUploadRequest(putObjectRequest.getBucketName(),
                        putObjectRequest.getKey(), uploadId, collectPartETags()));

        uploadComplete();

        UploadResult uploadResult = new UploadResult();
        uploadResult.setBucketName(completeMultipartUploadResult.getBucketName());
        uploadResult.setKey(completeMultipartUploadResult.getKey());
        uploadResult.setETag(completeMultipartUploadResult.getETag());
        uploadResult.setVersionId(completeMultipartUploadResult.getVersionId());
        return uploadResult;
    }

    private List<PartETag> collectPartETags() {

        final List<PartETag> partETags = new ArrayList<PartETag>();
        partETags.addAll(multipartUploadCallable.getETags());
        for (Future<PartETag> future : futures) {
            try {
                partETags.add(future.get());
            } catch (Exception e) {
                throw new AmazonClientException("Unable to upload part: " + e.getCause().getMessage(), e.getCause());
            }
        }
        return partETags;
    }

    /**
     * Cancels the futures in the following cases - If the user has requested
     * for forcefully aborting the transfers. - If the upload is a multi part
     * parellel upload. - If the upload operation hasn't started. Cancels all
     * the in flight transfers of the upload if applicable. Returns the
     * multi-part upload Id in case of the parallel multi-part uploads. Returns
     * null otherwise.
     */
    PauseResult<PersistableUpload> pause(boolean forceCancel) {

        PersistableUpload persistableUpload = multipartUploadCallable
                .getPersistableUpload();
        if (persistableUpload == null) {
            PauseStatus pauseStatus = TransferManagerUtils
                    .determinePauseStatus(transfer.getState(), forceCancel);
            if (forceCancel) {
                cancelFutures();
                multipartUploadCallable.performAbortMultipartUpload();
            }
            return new PauseResult<PersistableUpload>(pauseStatus);
        }
        cancelFutures();
        return new PauseResult<PersistableUpload>(PauseStatus.SUCCESS,
                persistableUpload);
    }

    /**
     * Cancels the inflight transfers if they are not completed.
     */
    private void cancelFutures() {
        nextFuture.cancel(true);
        for (Future<PartETag> f : futures) {
            f.cancel(true);
        }
        multipartUploadCallable.getFutures().clear();
        futures.clear();
    }

    /**
     * Cancels all the futures associated with this upload operation. Also
     * cleans up the parts on Amazon S3 if the uplaod is performed as a
     * multi-part upload operation.
     */
    void performAbort() {
        cancelFutures();
        multipartUploadCallable.performAbortMultipartUpload();
    }
}
