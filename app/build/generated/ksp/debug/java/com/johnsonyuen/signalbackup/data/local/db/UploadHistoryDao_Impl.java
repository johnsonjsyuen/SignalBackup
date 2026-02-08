package com.johnsonyuen.signalbackup.data.local.db;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class UploadHistoryDao_Impl implements UploadHistoryDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<UploadHistoryEntity> __insertionAdapterOfUploadHistoryEntity;

  public UploadHistoryDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfUploadHistoryEntity = new EntityInsertionAdapter<UploadHistoryEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `upload_history` (`id`,`timestamp`,`fileName`,`fileSizeBytes`,`status`,`errorMessage`,`driveFolderId`,`driveFileId`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final UploadHistoryEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getTimestamp());
        statement.bindString(3, entity.getFileName());
        statement.bindLong(4, entity.getFileSizeBytes());
        statement.bindString(5, entity.getStatus());
        if (entity.getErrorMessage() == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.getErrorMessage());
        }
        statement.bindString(7, entity.getDriveFolderId());
        if (entity.getDriveFileId() == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.getDriveFileId());
        }
      }
    };
  }

  @Override
  public Object insert(final UploadHistoryEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfUploadHistoryEntity.insert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<UploadHistoryEntity>> getAll() {
    final String _sql = "SELECT * FROM upload_history ORDER BY timestamp DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"upload_history"}, new Callable<List<UploadHistoryEntity>>() {
      @Override
      @NonNull
      public List<UploadHistoryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSizeBytes");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfErrorMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "errorMessage");
          final int _cursorIndexOfDriveFolderId = CursorUtil.getColumnIndexOrThrow(_cursor, "driveFolderId");
          final int _cursorIndexOfDriveFileId = CursorUtil.getColumnIndexOrThrow(_cursor, "driveFileId");
          final List<UploadHistoryEntity> _result = new ArrayList<UploadHistoryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final UploadHistoryEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final long _tmpFileSizeBytes;
            _tmpFileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final String _tmpErrorMessage;
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null;
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage);
            }
            final String _tmpDriveFolderId;
            _tmpDriveFolderId = _cursor.getString(_cursorIndexOfDriveFolderId);
            final String _tmpDriveFileId;
            if (_cursor.isNull(_cursorIndexOfDriveFileId)) {
              _tmpDriveFileId = null;
            } else {
              _tmpDriveFileId = _cursor.getString(_cursorIndexOfDriveFileId);
            }
            _item = new UploadHistoryEntity(_tmpId,_tmpTimestamp,_tmpFileName,_tmpFileSizeBytes,_tmpStatus,_tmpErrorMessage,_tmpDriveFolderId,_tmpDriveFileId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<UploadHistoryEntity> getLatest() {
    final String _sql = "SELECT * FROM upload_history ORDER BY timestamp DESC LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"upload_history"}, new Callable<UploadHistoryEntity>() {
      @Override
      @Nullable
      public UploadHistoryEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfFileName = CursorUtil.getColumnIndexOrThrow(_cursor, "fileName");
          final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSizeBytes");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfErrorMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "errorMessage");
          final int _cursorIndexOfDriveFolderId = CursorUtil.getColumnIndexOrThrow(_cursor, "driveFolderId");
          final int _cursorIndexOfDriveFileId = CursorUtil.getColumnIndexOrThrow(_cursor, "driveFileId");
          final UploadHistoryEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final String _tmpFileName;
            _tmpFileName = _cursor.getString(_cursorIndexOfFileName);
            final long _tmpFileSizeBytes;
            _tmpFileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final String _tmpErrorMessage;
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null;
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage);
            }
            final String _tmpDriveFolderId;
            _tmpDriveFolderId = _cursor.getString(_cursorIndexOfDriveFolderId);
            final String _tmpDriveFileId;
            if (_cursor.isNull(_cursorIndexOfDriveFileId)) {
              _tmpDriveFileId = null;
            } else {
              _tmpDriveFileId = _cursor.getString(_cursorIndexOfDriveFileId);
            }
            _result = new UploadHistoryEntity(_tmpId,_tmpTimestamp,_tmpFileName,_tmpFileSizeBytes,_tmpStatus,_tmpErrorMessage,_tmpDriveFolderId,_tmpDriveFileId);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
