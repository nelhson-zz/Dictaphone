package org.borisveriga.soundrecorder.fragments;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.budiyev.android.circularprogressbar.CircularProgressBar;
import com.melnykov.fab.FloatingActionButton;

import org.borisveriga.soundrecorder.R;
import org.borisveriga.soundrecorder.model.dao.RecorderState;
import org.borisveriga.soundrecorder.activities.MainActivity;
import org.borisveriga.soundrecorder.listeners.OnSingleClickListener;
import org.borisveriga.soundrecorder.services.RecordingService;
import org.borisveriga.soundrecorder.util.Command;
import org.borisveriga.soundrecorder.util.EventBroadcaster;
import org.borisveriga.soundrecorder.util.MyIntentBuilder;
import org.borisveriga.soundrecorder.util.Paths;
import org.borisveriga.soundrecorder.util.PermissionsHelper;
import org.borisveriga.soundrecorder.util.ScreenLock;

import java.io.File;

public class RecordFragment extends Fragment {
    private static final String LOG_TAG = RecordFragment.class.getSimpleName();
    private static final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO_RESUME = 2;

    private FloatingActionButton mRecordButton = null;
    private Button mPauseButton = null;
    private boolean isRecordButtonInState1 = true;
    private boolean isPauseButtonInState1 = true;

    private CircularProgressBar mProgressBar;

    private TextView mRecordingPrompt;
    private int mRecordPromptCount = 0;

    private Chronometer mChronometer = null;
    long timeWhenPaused = 0;

    private ServiceConnection mConnection;
    private RecordingService mRecordingService;
    private BroadcastReceiver mMessageReceiver = null;

    public static RecordFragment newInstance() {
        RecordFragment f = new RecordFragment();
        Bundle b = new Bundle();
        f.setArguments(b);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final RecorderState newState = (RecorderState) intent.getSerializableExtra(
                        EventBroadcaster.NEW_STATE);
                if (RecorderState.STOPPED.equals(newState)) {
                    updateUI(newState, SystemClock.elapsedRealtime());

                    if (intent.getStringExtra(EventBroadcaster.LAST_AUDIO_LOCATION) != null && MainActivity.REQUEST_INTENTS.contains(requireActivity().getIntent().getAction())) {

                        getActivity().setResult(Activity.RESULT_OK, new Intent().setData(Uri.fromFile(new File(intent.getStringExtra(EventBroadcaster.LAST_AUDIO_LOCATION)))));
                        getActivity().finish();

                    }
                } else if (RecorderState.RECORDING.equals(newState)) {
                    long chronometerTime = intent.getLongExtra(
                            EventBroadcaster.CHRONOMETER_TIME,
                            SystemClock.elapsedRealtime()
                    );
                    updateUI(newState, chronometerTime);
                }
            }
        };
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        final Intent intent = new Intent(context, RecordingService.class);
        tryBindService(context, intent);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        tryUnbindService(getContext());
    }

    private void tryBindService(Context context, Intent intent) {
        if (mConnection == null) {
            mConnection = new ServiceConnection() {

                @Override
                public void onServiceConnected(ComponentName className, IBinder service) {
                    RecordingService.LocalBinder binder = (RecordingService.LocalBinder) service;
                    mRecordingService = binder.getService();
                    Log.i(LOG_TAG, "onServiceConnected");

                    long chronometerTime = SystemClock.elapsedRealtime() - mRecordingService.getTotalDurationMillis();
                    updateUI(mRecordingService.getState(), chronometerTime);
                }

                @Override
                public void onServiceDisconnected(ComponentName arg0) {
                    Log.i(LOG_TAG, "onServiceDisconnected");
                    mRecordingService = null;
                }
            };

            context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void tryUnbindService(Context context) {
        if (context == null) {
            Log.w(LOG_TAG, "tryUnbindService: context is null");
        }
        if (mConnection != null) {
            context.unbindService(mConnection);
            mConnection = null;
        }
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View recordView = inflater.inflate(R.layout.fragment_record, container, false);

        mChronometer = recordView.findViewById(R.id.chronometer);

        mRecordingPrompt = recordView.findViewById(R.id.recording_status_text);

        mRecordButton = recordView.findViewById(R.id.btnRecord);
        mRecordButton.setOnClickListener(createRecordButtonClickListener());

        mProgressBar = recordView.findViewById(R.id.recordProgressBar);
        mPauseButton = recordView.findViewById(R.id.btnPause);
        mPauseButton.setVisibility(View.GONE);
        mPauseButton.setOnClickListener(createPauseButtonClickListener());

        return recordView;
    }

    private final Chronometer.OnChronometerTickListener listener = new Chronometer.OnChronometerTickListener() {
        @Override
        public void onChronometerTick(Chronometer chronometer) {
            if (mRecordPromptCount == 0) {
                mRecordingPrompt.setText(getString(R.string.record_in_progress) + ".");
            } else if (mRecordPromptCount == 1) {
                mRecordingPrompt.setText(getString(R.string.record_in_progress) + "..");
            } else if (mRecordPromptCount == 2) {
                mRecordingPrompt.setText(getString(R.string.record_in_progress) + "...");
                mRecordPromptCount = -1;
            }

            ++mRecordPromptCount;
        }
    };

    private View.OnClickListener createPauseButtonClickListener() {
        return new OnSingleClickListener() {
            @Override
            public void onSingleClick(View v) {
                if (isPauseButtonInState1) {
                    mRecordingService.pauseRecording();
                    long chronometerTime = SystemClock.elapsedRealtime() - mRecordingService.getTotalDurationMillis();
                    updateUI(RecorderState.PAUSED, chronometerTime);
                } else {
                    if (PermissionsHelper.checkAndRequestPermissions(
                            RecordFragment.this,
                            MY_PERMISSIONS_REQUEST_RECORD_AUDIO
                    )) {
                        resumeRecording();
                    }
                }

            }
        };
    }

    private View.OnClickListener createRecordButtonClickListener() {
        return new OnSingleClickListener() {
            @Override
            public void onSingleClick(View v) {
                if (isRecordButtonInState1) {
                    if (PermissionsHelper.checkAndRequestPermissions(
                            RecordFragment.this, MY_PERMISSIONS_REQUEST_RECORD_AUDIO)) {
                        startRecording();
                    }
                } else {
                    stopRecording();
                }
            }
        };
    }

    private Intent getStartServiceIntent() {
        return MyIntentBuilder
                .getInstance(requireActivity(), RecordingService.class)
                .setCommand(Command.START)
                .build();
    }

    private Intent getStopServiceIntent() {
        return MyIntentBuilder
                .getInstance(requireActivity(), RecordingService.class)
                .setCommand(Command.STOP)
                .build();
    }

    private void updateUI(RecorderState state, long chronometerBaseTime) {
        if (getActivity() == null || !isAdded()) {
            Log.i(LOG_TAG, "RecordFragment is not attached to an Activity");
            return;
        }
        Log.i(LOG_TAG, "new state is " + state + ", time is " + chronometerBaseTime + " ms");

        switch (state) {
            case STOPPED:
                mRecordButton.show();
                mRecordButton.setImageResource(R.drawable.ic_mic_white_36dp);
                mPauseButton.setVisibility(View.GONE);
                timeWhenPaused = 0;
                mRecordingPrompt.setText(getString(R.string.record_prompt));

                isPauseButtonInState1 = true;
                isRecordButtonInState1 = true;

                mChronometer.setOnChronometerTickListener(null);
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.stop();

                mProgressBar.setIndeterminate(false);
                break;

            case PREPARING:
                mRecordingPrompt.setText(getString(R.string.wait));
                mProgressBar.setIndeterminate(true);
                break;

            case RECORDING:
                mRecordButton.setImageResource(R.drawable.ic_media_stop);
                mPauseButton.setCompoundDrawablesWithIntrinsicBounds
                        (R.drawable.ic_media_pause, 0, 0, 0);
                mPauseButton.setText(getString(R.string.pause_recording_button).toUpperCase());
                mPauseButton.setVisibility(View.VISIBLE);
                mRecordingPrompt.setText(getString(R.string.record_in_progress));

                isPauseButtonInState1 = true;
                isRecordButtonInState1 = false;

                mChronometer.setBase(chronometerBaseTime);
                mChronometer.setOnChronometerTickListener(listener);
                mChronometer.start();

                mProgressBar.setIndeterminate(true);
                break;

            case PAUSED:
                mRecordButton.setImageResource(R.drawable.ic_media_stop);
                mPauseButton.setCompoundDrawablesWithIntrinsicBounds
                        (R.drawable.ic_media_play, 0, 0, 0);
                mPauseButton.setText(getString(R.string.resume_recording_button).toUpperCase());
                mPauseButton.setVisibility(View.VISIBLE);
                mRecordingPrompt.setText(getString(R.string.record_paused));

                isPauseButtonInState1 = false;
                isRecordButtonInState1 = false;

                mChronometer.setOnChronometerTickListener(null);
                mChronometer.setBase(chronometerBaseTime);
                mChronometer.stop();

                mProgressBar.setIndeterminate(false);
                break;
        }
    }

    private void stopRecording() {
        final FragmentActivity activity = getActivity();
        if (activity == null) {
            Log.wtf(LOG_TAG, "RecordFragment failed to stop recording, getActivity() returns null.");
            return;
        }
        activity.startService(getStopServiceIntent());
        tryUnbindService(activity);

        ScreenLock.allowScreenTurnOff(activity);
    }

    private void startRecording() {
        final File folder = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Paths.SOUND_RECORDER_FOLDER
        );
        if (!folder.exists()) {
            boolean ok = Paths.isExternalStorageWritable() && folder.mkdir();
            if (!ok) {
                EventBroadcaster.send(getContext(), R.string.error_mkdir);
                return;
            }
        }

        final FragmentActivity activity = getActivity();
        if (activity == null) {
            Log.wtf(LOG_TAG, "failed to start recording, getActivity() returns null.");
            return;
        }

        final Intent intent = getStartServiceIntent();
        final ComponentName componentName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            componentName = activity.startForegroundService(intent);
        } else {
            componentName = activity.startService(intent);
        }
        tryBindService(activity, intent);
        ScreenLock.keepScreenOn(activity);

        if (componentName != null) {
            updateUI(RecorderState.PREPARING, SystemClock.elapsedRealtime());
        }

    }

    private void resumeRecording() {
        long chronometerTime = SystemClock.elapsedRealtime() - mRecordingService.getTotalDurationMillis();
        mRecordingService.startRecording();
        updateUI(RecorderState.PREPARING, chronometerTime);
    }

    @Override
    public void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                mMessageReceiver,
                new IntentFilter(EventBroadcaster.CHANGE_STATE)
        );
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mMessageReceiver);
        } catch (Exception exc) {
            exc.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_RECORD_AUDIO: {
                if (allPermissionsGranted(grantResults)) {
                    startRecording();
                } else {
                    EventBroadcaster.send(getContext(), R.string.error_no_permission_granted_record);
                }
                break;
            }

            case MY_PERMISSIONS_REQUEST_RECORD_AUDIO_RESUME: {
                if (allPermissionsGranted(grantResults)) {
                    resumeRecording();
                } else {
                    EventBroadcaster.send(getContext(), R.string.error_no_permission_granted_record);
                }
                break;
            }
        }
    }

    private boolean allPermissionsGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) {
            return false;
        }

        boolean ok = true;
        for (int grantResult : grantResults) {
            ok &= (grantResult == PackageManager.PERMISSION_GRANTED);
        }
        return ok;
    }

}
