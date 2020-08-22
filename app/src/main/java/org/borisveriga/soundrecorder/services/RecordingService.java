package org.borisveriga.soundrecorder.services;

import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;

import org.borisveriga.soundrecorder.model.local.DBHelper;
import org.borisveriga.soundrecorder.R;
import org.borisveriga.soundrecorder.model.dao.RecorderState;
import org.borisveriga.soundrecorder.util.Command;
import org.borisveriga.soundrecorder.util.EventBroadcaster;
import org.borisveriga.soundrecorder.util.MyIntentBuilder;
import org.borisveriga.soundrecorder.util.MySharedPreferences;
import org.borisveriga.soundrecorder.util.Paths;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;


public class RecordingService extends Service {

    private static final String LOG_TAG = "RecordingService";

    private String mFileName = null;
    private String mFilePath = null;

    private MediaRecorder mRecorder = null;

    private DBHelper mDatabase;

    private long mStartingTimeMillis = 0;
    private long mElapsedMillis = 0;

    private volatile RecorderState state = RecorderState.STOPPED;
    private int tempFileCount = 0;

    private ArrayList<String> filesPaused = new ArrayList<>();
    private ArrayList<Long> pauseDurations = new ArrayList<>();

    private final IBinder mBinder = new LocalBinder();


    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDatabase = new DBHelper(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean containsCommand = MyIntentBuilder.containsCommand(intent);
        Log.d(LOG_TAG, String.format(
                "Service in [%s] state. cmdId: [%s]. startId: [%d]",
                state,
                containsCommand ? MyIntentBuilder.getCommand(intent) : "N/A",
                startId));
        routeIntentToCommand(intent);

        return START_STICKY;
    }

    private void routeIntentToCommand(@Nullable Intent intent) {
        if (intent != null) {
            if (MyIntentBuilder.containsCommand(intent)) {
                processCommand(MyIntentBuilder.getCommand(intent));
            }
            if (MyIntentBuilder.containsMessage(intent)) {
                processMessage(MyIntentBuilder.getMessage(intent));
            }
        }
    }

    private void processMessage(String message) {
        try {
            Log.d(LOG_TAG, String.format("doMessage: message from client: '%s'", message));
        } catch (Exception e) {
            Log.e(LOG_TAG, "processMessage: exception", e);
        }
    }

    private void processCommand(@Command int command) {
        try {
            switch (command) {
                case Command.START:
                    startRecording();
                    break;
                case Command.PAUSE:
                    pauseRecording();
                    break;
                case Command.STOP:
                    stopService();
                    break;
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "processCommand: exception", e);
        }
    }

    public void stopService() {
        Log.d(LOG_TAG, "RecordingService#stopService()");
        stopRecording();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mRecorder != null) {
            stopRecording();
        }

        super.onDestroy();
    }

    public void setFileNameAndPath(boolean isFilePathTemp) {
        if (isFilePathTemp) {
            mFileName = getString(R.string.default_file_name) + (++tempFileCount) + "_" + ".tmp";
            Paths.createDirectory(getExternalCacheDir(), Paths.SOUND_RECORDER_FOLDER);
            mFilePath = Paths.combine(
                    getExternalCacheDir(),
                    Paths.SOUND_RECORDER_FOLDER, mFileName);
        } else {
            int count = 0;
            File f;

            do {
                ++count;

                mFileName =
                        getString(R.string.default_file_name) + "_" + (mDatabase.getCount() + count) + ".mp4";

                mFilePath = Paths.combine(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        Paths.SOUND_RECORDER_FOLDER, mFileName);

                f = new File(mFilePath);
            } while (f.exists() && !f.isDirectory());
        }
    }

    public void startRecording() {
        if (state == RecorderState.RECORDING || state == RecorderState.PREPARING)
            return;
        changeStateTo(RecorderState.PREPARING);

        boolean isTemporary = true;
        setFileNameAndPath(isTemporary);

        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setOutputFile(mFilePath);
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mRecorder.setAudioChannels(1);
        if (MySharedPreferences.getPrefHighQuality(this)) {
            mRecorder.setAudioSamplingRate(44100);
            mRecorder.setAudioEncodingBitRate(192000);
        }

        try {
            final long totalDurationMillis = getTotalDurationMillis();
            mRecorder.prepare();
            mRecorder.start();
            changeStateTo(RecorderState.RECORDING);
            Toast.makeText(this, R.string.toast_recording_start, Toast.LENGTH_SHORT).show();
            mStartingTimeMillis = SystemClock.elapsedRealtime();
            EventBroadcaster.startRecording(this, mStartingTimeMillis - totalDurationMillis);
        } catch (IOException e) {
            changeStateTo(RecorderState.STOPPED);
            EventBroadcaster.stopRecording(this);
            Log.e(LOG_TAG, "prepare() failed", e);
            EventBroadcaster.send(this, getString(R.string.error_unknown));
        } catch (IllegalStateException e) {
            changeStateTo(RecorderState.STOPPED);
            EventBroadcaster.stopRecording(this);
            Log.e(LOG_TAG, "start() failed", e);
            EventBroadcaster.send(this, getString(R.string.error_mic_is_busy));
        }
    }

    public void pauseRecording() {
        if (state != RecorderState.RECORDING)
            return;
        changeStateTo(RecorderState.PREPARING);

        try {
            mElapsedMillis = (SystemClock.elapsedRealtime() - mStartingTimeMillis);
            pauseDurations.add(mElapsedMillis);
            mRecorder.stop();
            changeStateTo(RecorderState.PAUSED);
            Toast.makeText(this, getString(R.string.toast_recording_paused), Toast.LENGTH_LONG).show();

            filesPaused.add(mFilePath);
        } catch (IllegalStateException exc) {
            changeStateTo(RecorderState.RECORDING);
            Log.e(LOG_TAG, "stop() failed", exc);
        }
    }

    public void stopRecording() {
        if (state == RecorderState.STOPPED) {
            Log.wtf(LOG_TAG, "stopRecording: already STOPPED.");
            return;
        }
        if (state == RecorderState.PREPARING)
            return;
        final RecorderState stateBefore = state;
        changeStateTo(RecorderState.PREPARING);
        if (stateBefore == RecorderState.RECORDING)
            filesPaused.add(mFilePath);

        boolean isTemporary = false;
        setFileNameAndPath(isTemporary);
        String pathToSend = "";
        try {
            if (stateBefore != RecorderState.PAUSED) {
                mElapsedMillis = (SystemClock.elapsedRealtime() - mStartingTimeMillis);
                mRecorder.stop();
            }
            mRecorder.release();
            pathToSend = mFilePath;
            Toast.makeText(this, getString(R.string.toast_recording_finish) + " " + mFilePath, Toast.LENGTH_LONG).show();
        } catch (RuntimeException exc) {
            Log.e(LOG_TAG, "RuntimeException: stop() is called immediately after start()", exc);
            pathToSend = null;
        } finally {
            mRecorder = null;
            changeStateTo(RecorderState.STOPPED);
            EventBroadcaster.stopRecording(this, pathToSend);
        }

        if (filesPaused != null && !filesPaused.isEmpty()) {
            if (makeSingleFile(filesPaused)) {
                for (long duration : pauseDurations)
                    mElapsedMillis += duration;
            }
        }

        try {
            mDatabase.addRecording(mFileName, mFilePath, mElapsedMillis);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(LOG_TAG, "exception", e);
        }
    }

    private boolean makeSingleFile(ArrayList<String> filesPaused) {
        ArrayList<Track> tracks = new ArrayList<>();
        Movie finalMovie = new Movie();
        for (String filePath : filesPaused) {
            try {
                Movie movie = MovieCreator.build(filePath);
                List<Track> movieTracks = movie.getTracks();
                tracks.addAll(movieTracks);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            } catch (NullPointerException exc) {
                exc.printStackTrace();
            }
        }

        if (tracks.size() > 0) {
            try {
                finalMovie.addTrack(new AppendTrack(tracks.toArray(new Track[0])));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        final Container mp4file;
        final FileChannel fc;
        try {
            mp4file = new DefaultMp4Builder().build(finalMovie);
            fc = new FileOutputStream(new File(mFilePath)).getChannel();
        } catch (NoSuchElementException exc) {
            exc.printStackTrace();
            return false;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }

        boolean ok = true;
        try {
            mp4file.writeContainer(fc);
        } catch (IOException e) {
            e.printStackTrace();
            ok = false;
        } finally {
            try {
                fc.close();
            } catch (IOException exc) {
                exc.printStackTrace();
                ok = false;
            }
        }

        return ok;
    }

    public long getElapsedMillis() {
        return mElapsedMillis;
    }

    public long getStartingTimeMillis() {
        return mStartingTimeMillis;
    }

    public long getTotalDurationMillis() {
        long total = 0;
        if (pauseDurations == null || pauseDurations.isEmpty()) {
            total += mElapsedMillis;
        } else {
            for (long duration : pauseDurations)
                total += duration;
        }
        if (state == RecorderState.RECORDING) {
            total += (SystemClock.elapsedRealtime() - mStartingTimeMillis);
        }

        return total;
    }

    public RecorderState getState() {
        return state;
    }

    private void changeStateTo(RecorderState newState) {
        if (state == RecorderState.PREPARING && newState == RecorderState.PREPARING)
            throw new IllegalStateException();
        state = newState;
    }

    public class LocalBinder extends Binder {
        public RecordingService getService() {
            return RecordingService.this;
        }
    }

}
