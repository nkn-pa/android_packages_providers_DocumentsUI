/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui.archives;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.LruCache;

import com.android.documentsui.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;

/**
 * Provides basic implementation for creating, extracting and accessing
 * files within archives exposed by a document provider.
 *
 * <p>This class is thread safe. All methods can be called on any thread without
 * synchronization.
 */
public class ArchivesProvider extends DocumentsProvider implements Closeable {
    public static final String AUTHORITY = "com.android.documentsui.archives";

    private static final String TAG = "ArchivesProvider";
    private static final String METHOD_CLOSE_ARCHIVE = "closeArchive";
    private static final int OPENED_ARCHIVES_CACHE_SIZE = 4;
    private static final String[] ZIP_MIME_TYPES = {
            "application/zip", "application/x-zip", "application/x-zip-compressed"
    };

    @GuardedBy("mArchives")
    private final LruCache<Key, Loader> mArchives =
            new LruCache<Key, Loader>(OPENED_ARCHIVES_CACHE_SIZE) {
                @Override
                public void entryRemoved(boolean evicted, Key key,
                        Loader oldValue, Loader newValue) {
                    oldValue.getWriteLock().lock();
                    try {
                        oldValue.get().close();
                    } catch (IOException e) {
                        Log.e(TAG, "Closing archive failed.", e);
                    }finally {
                        oldValue.getWriteLock().unlock();
                    }
                }
            };

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_CLOSE_ARCHIVE.equals(method)) {
            closeArchive(arg);
            return null;
        }

        return super.call(method, arg, extras);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Cursor queryChildDocuments(String documentId, @Nullable String[] projection,
            @Nullable String sortOrder)
            throws FileNotFoundException {
        final ArchiveId archiveId = ArchiveId.fromDocumentId(documentId);
        Loader loader = null;
        try {
            loader = obtainInstance(documentId);
            final int status = loader.getStatus();
            // If already loaded, then forward the request to the archive.
            if (status == Loader.STATUS_OPENED) {
                return loader.get().queryChildDocuments(documentId, projection, sortOrder);
            }

            final MatrixCursor cursor = new MatrixCursor(
                    projection != null ? projection : Archive.DEFAULT_PROJECTION);
            final Bundle bundle = new Bundle();

            switch (status) {
                case Loader.STATUS_OPENING:
                    bundle.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                    break;

                case Loader.STATUS_FAILED:
                    // Return an empty cursor with EXTRA_LOADING, which shows spinner
                    // in DocumentsUI. Once the archive is loaded, the notification will
                    // be sent, and the directory reloaded.
                    bundle.putString(DocumentsContract.EXTRA_ERROR,
                            getContext().getString(R.string.archive_loading_failed));
                    break;
            }

            cursor.setExtras(bundle);
            cursor.setNotificationUri(getContext().getContentResolver(),
                    buildUriForArchive(archiveId.mArchiveUri, archiveId.mAccessMode));
            return cursor;
        } finally {
            releaseInstance(loader);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        final ArchiveId archiveId = ArchiveId.fromDocumentId(documentId);
        if (archiveId.mPath.equals("/")) {
            return Document.MIME_TYPE_DIR;
        }

        Loader loader = null;
        try {
            loader = obtainInstance(documentId);
            return loader.get().getDocumentType(documentId);
        } finally {
            releaseInstance(loader);
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        Loader loader = null;
        try {
            loader = obtainInstance(documentId);
            return loader.get().isChildDocument(parentDocumentId, documentId);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        } finally {
            releaseInstance(loader);
        }
    }

    @Override
    public Cursor queryDocument(String documentId, @Nullable String[] projection)
            throws FileNotFoundException {
        final ArchiveId archiveId = ArchiveId.fromDocumentId(documentId);
        if (archiveId.mPath.equals("/")) {
            try (final Cursor archiveCursor = getContext().getContentResolver().query(
                    archiveId.mArchiveUri,
                    new String[] { Document.COLUMN_DISPLAY_NAME },
                    null, null, null, null)) {
                if (archiveCursor == null || !archiveCursor.moveToFirst()) {
                    throw new FileNotFoundException(
                            "Cannot resolve display name of the archive.");
                }
                final String displayName = archiveCursor.getString(
                        archiveCursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME));

                final MatrixCursor cursor = new MatrixCursor(
                        projection != null ? projection : Archive.DEFAULT_PROJECTION);
                final RowBuilder row = cursor.newRow();
                row.add(Document.COLUMN_DOCUMENT_ID, documentId);
                row.add(Document.COLUMN_DISPLAY_NAME, displayName);
                row.add(Document.COLUMN_SIZE, 0);
                row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
                return cursor;
            }
        }

        Loader loader = null;
        try {
            loader = obtainInstance(documentId);
            return loader.get().queryDocument(documentId, projection);
        } finally {
            releaseInstance(loader);
        }
    }

    @Override
    public String createDocument(
            String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        Loader loader = null;
        try {
            loader = obtainInstance(parentDocumentId);
            return loader.get().createDocument(parentDocumentId, mimeType, displayName);
        } finally {
            releaseInstance(loader);
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, final CancellationSignal signal)
            throws FileNotFoundException {
        Loader loader = null;
        try {
            loader = obtainInstance(documentId);
            return loader.get().openDocument(documentId, mode, signal);
        } finally {
            releaseInstance(loader);
        }
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, final CancellationSignal signal)
            throws FileNotFoundException {
        Loader loader = null;
        try {
            loader = obtainInstance(documentId);
            return loader.get().openDocumentThumbnail(documentId, sizeHint, signal);
        } finally {
            releaseInstance(loader);
        }
    }

    /**
     * Returns true if the passed mime type is supported by the helper.
     */
    public static boolean isSupportedArchiveType(String mimeType) {
        for (final String zipMimeType : ZIP_MIME_TYPES) {
            if (zipMimeType.equals(mimeType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a Uri for accessing an archive with the specified access mode.
     *
     * @see ParcelFileDescriptor#MODE_READ
     * @see ParcelFileDescriptor#MODE_WRITE
     */
    public static Uri buildUriForArchive(Uri externalUri, int accessMode) {
        return DocumentsContract.buildDocumentUri(AUTHORITY,
                new ArchiveId(externalUri, accessMode, "/").toDocumentId());
    }

    /**
     * Closes an archive.
     */
    public static void closeArchive(ContentResolver resolver, Uri archiveUri) {
        Archive.MorePreconditions.checkArgumentEquals(AUTHORITY, archiveUri.getAuthority(),
                "Mismatching authority. Expected: %s, actual: %s.");
        final String documentId = DocumentsContract.getDocumentId(archiveUri);
        final ArchiveId archiveId = ArchiveId.fromDocumentId(documentId);

        try (final ContentProviderClient client = resolver.acquireUnstableContentProviderClient(
                AUTHORITY)) {
            client.call(METHOD_CLOSE_ARCHIVE, documentId, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to close archive.", e);
        }
    }

    /**
     * Closes an archive.
     */
    public void closeArchive(String documentId) {
        final ArchiveId archiveId = ArchiveId.fromDocumentId(documentId);
        synchronized (mArchives) {
            mArchives.remove(Key.fromArchiveId(archiveId));
        }
    }

    /**
     * Closes the helper and disposes all existing archives. It will block until all ongoing
     * operations on each opened archive are finished.
     */
    @Override
    // TODO: Wire close() to call().
    public void close() {
        synchronized (mArchives) {
            mArchives.evictAll();
        }
    }

    private Loader obtainInstance(String documentId) throws FileNotFoundException {
        Loader loader;
        synchronized (mArchives) {
            loader = getInstanceUncheckedLocked(documentId);
            loader.getReadLock().lock();
        }
        return loader;
    }

    private void releaseInstance(@Nullable Loader loader) {
        if (loader != null) {
            loader.getReadLock().unlock();
        }
    }

    private Loader getInstanceUncheckedLocked(String documentId) throws FileNotFoundException {
        final ArchiveId id = ArchiveId.fromDocumentId(documentId);
        final Key key = Key.fromArchiveId(id);
        final Loader existingLoader = mArchives.get(key);
        if (existingLoader != null) {
            return existingLoader;
        }

        final Cursor cursor = getContext().getContentResolver().query(
                id.mArchiveUri, new String[] { Document.COLUMN_MIME_TYPE }, null, null, null);
        if (cursor == null || cursor.getCount() == 0) {
            throw new FileNotFoundException("File not found." + id.mArchiveUri);
        }

        cursor.moveToFirst();
        final String mimeType = cursor.getString(cursor.getColumnIndex(
                Document.COLUMN_MIME_TYPE));
        Preconditions.checkArgument(isSupportedArchiveType(mimeType));
        final Uri notificationUri = cursor.getNotificationUri();
        final Loader loader = new Loader(getContext(), id.mArchiveUri, id.mAccessMode,
                notificationUri);

        // Remove the instance from mArchives collection once the archive file changes.
        if (notificationUri != null) {
            final LruCache<Key, Loader> finalArchives = mArchives;
            getContext().getContentResolver().registerContentObserver(notificationUri,
                    false,
                    new ContentObserver(null) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            synchronized (mArchives) {
                                final Loader currentLoader = mArchives.get(key);
                                if (currentLoader == loader) {
                                    mArchives.remove(key);
                                }
                            }
                        }
                    });
        }

        mArchives.put(key, loader);
        return loader;
    }

    private static class Key {
        Uri archiveUri;
        int accessMode;

        public Key(Uri archiveUri, int accessMode) {
            this.archiveUri = archiveUri;
            this.accessMode = accessMode;
        }

        public static Key fromArchiveId(ArchiveId id) {
            return new Key(id.mArchiveUri, id.mAccessMode);
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            return archiveUri.equals(((Key) other).archiveUri) &&
                accessMode == ((Key) other).accessMode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(archiveUri, accessMode);
        }
    }
}
