package org.borisveriga.soundrecorder.fragments;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialogFragment;
import org.borisveriga.soundrecorder.R;
import org.borisveriga.soundrecorder.model.dao.RecordingItem;
import org.borisveriga.soundrecorder.listeners.HeadsetListener;
import org.borisveriga.soundrecorder.listeners.OnSingleClickListener;
import org.borisveriga.soundrecorder.util.AudioManagerCompat;
import org.borisveriga.soundrecorder.util.EventBroadcaster;
import org.borisveriga.soundrecorder.util.ScreenLock;
import org.borisveriga.soundrecorder.util.TimeUtils;

import static android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY;
import static android.media.AudioManager.ACTION_HEADSET_PLUG;
import static androidx.core.content.ContextCompat.checkSelfPermission;


public class PlaybackFragment extends AppCompatDialogFragment {

    private static final String LOG_TAG = "PlaybackFragment";
    private static final String ARG_ITEM = "recording_item";
    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 3;

    private RecordingItem item;

    private Handler mHandler = new Handler();
    private HeadsetListener mHeadsetListener;

    private MediaPlayer mMediaPlayer = null;

    private SeekBar mSeekBar = null;
    private FloatingActionButton mPlayButton = null;
    private TextView mCurrentProgressTextView = null;
    private TextView mFileLengthTextView = null;

    private volatile boolean isPlaying = false;

    private long itemDurationMs = 0;

    /**
     * Whether this PlaybackFragment has focus from {@link AudioManager} to play audio
     * @see #requestAudioFocus()
     * @see #abandonAudioFocus()
     */
    private boolean mFocused = false;
    /**
     * Whether playback should continue once {@link AudioManager} returns focus to this PlaybackFragment
     */
    private boolean mResumeOnFocusGain = false;
    /**
     * The volume scalar to set when {@link AudioManager} causes this PlaybackFragment to duck
     */
    private static final float DUCK_VOLUME = 0.2f;

    public PlaybackFragment newInstance(RecordingItem item) {
        PlaybackFragment f = new PlaybackFragment();
        Bundle b = new Bundle();
        b.putParcelable(ARG_ITEM, item);
        f.setArguments(b);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Bundle args;
        if (savedInstanceState == null) {
            args = getArguments();
        } else {
            args = savedInstanceState;
        }
        if (args == null) {
            throw new IllegalArgumentException("Bundle args required");
        }

        item = args.getParcelable(ARG_ITEM);
        if (item == null)
            return;

        itemDurationMs = item.getLength();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        Dialog dialog = super.onCreateDialog(savedInstanceState);

        final AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        final View view = requireActivity().getLayoutInflater().inflate(R.layout.fragment_media_playback, null);

        final TextView fileNameTextView = view.findViewById(R.id.file_name_text_view);
        mFileLengthTextView = view.findViewById(R.id.file_length_text_view);
        mCurrentProgressTextView = view.findViewById(R.id.current_progress_text_view);

        mSeekBar = view.findViewById(R.id.seekbar);
        ColorFilter filter = new LightingColorFilter
                (getResources().getColor(R.color.primary), getResources().getColor(R.color.primary));
        mSeekBar.getProgressDrawable().setColorFilter(filter);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mSeekBar.getThumb().setColorFilter(filter);
        }

        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mMediaPlayer != null && fromUser) {
                    mMediaPlayer.seekTo(progress);
                    mHandler.removeCallbacks(mRunnable);

                    final int currentPosition = mMediaPlayer.getCurrentPosition();
                    mCurrentProgressTextView.setText(TimeUtils.formatDuration(currentPosition));
                    updateSeekBar();
                } else if (mMediaPlayer == null && fromUser) {
                    prepareMediaPlayerFromPoint(progress);
                    updateSeekBar();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (mMediaPlayer != null) {
                    mHandler.removeCallbacks(mRunnable);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mMediaPlayer != null) {
                    mHandler.removeCallbacks(mRunnable);
                    mMediaPlayer.seekTo(seekBar.getProgress());

                    final int currentPosition = mMediaPlayer.getCurrentPosition();
                    mCurrentProgressTextView.setText(TimeUtils.formatDuration(currentPosition));
                    updateSeekBar();
                }
            }
        });

        mPlayButton = view.findViewById(R.id.fab_play);
        mPlayButton.setOnClickListener(new OnSingleClickListener() {
            @Override
            public void onSingleClick(View v) {
                isPlaying = onPlay(isPlaying);
            }
        });

        fileNameTextView.setText(item.getName());
        mFileLengthTextView.setText(TimeUtils.formatDuration(itemDurationMs));

        builder.setView(view);

        final Window window = dialog.getWindow();
        if (window != null) {
            window.requestFeature(Window.FEATURE_NO_TITLE);
        }

        return builder.create();
    }

    /**
     * Attach a HeadsetListener to respond to headset events.
     */
    private void attachHeadsetListener(Context context) {
        mHeadsetListener = new HeadsetListener(this);
        IntentFilter filter = new IntentFilter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            filter.addAction(ACTION_HEADSET_PLUG);
        }
        filter.addAction(ACTION_AUDIO_BECOMING_NOISY);
        if (context != null) {
            context.registerReceiver(mHeadsetListener, filter);
        } else {
            Log.wtf(LOG_TAG, "attachHeadsetListener(): context is null.");
        }
    }

    /**
     * Detach a HeadsetListener to respond to headset events.
     */
    private void detachHeadsetListener() {
        final Context context = getContext();
        if (context != null) {
            context.unregisterReceiver(mHeadsetListener);
        } else {
            Log.wtf(LOG_TAG, "detachHeadsetListener(): getContext() returned null.");
        }
        mHeadsetListener = null;
    }

    @Override
    public void onStart() {
        super.onStart();

        Window window = getDialog().getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        AlertDialog alertDialog = (AlertDialog) getDialog();
        alertDialog.getButton(Dialog.BUTTON_POSITIVE).setEnabled(false);
        alertDialog.getButton(Dialog.BUTTON_NEGATIVE).setEnabled(false);
        alertDialog.getButton(Dialog.BUTTON_NEUTRAL).setEnabled(false);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mMediaPlayer != null) {
            stopPlaying();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mMediaPlayer != null) {
            stopPlaying();
        }
    }

    /**
     * @param context a reference to the newly created Activity after each configuration change.
     */
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (mHeadsetListener == null) {
            attachHeadsetListener(context);
        }
    }

    /**
     * Set the MediaPlayer and Headset listener to null so we don't accidentally
     * leak the Activity instance.
     */
    @Override
    public void onDetach() {
        super.onDetach();
        if (mHeadsetListener != null) {
            detachHeadsetListener();
        }
    }

    public void tapStartButton() {
        isPlaying = onPlay(false);
    }

    public void tapStopButton() {
        isPlaying = onPlay(true);
    }

    private boolean onPlay(boolean isPlaying) {
        if (!isPlaying) {
            if (getContext() == null) {
                return isPlaying;
            }

            if (checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                this.requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
                return isPlaying;
            }
            startOrResumePlaying();
        } else {
            pausePlaying();
            abandonAudioFocus();
        }
        return !isPlaying;
    }

    private void startOrResumePlaying() {
        if (mMediaPlayer == null) {
            startPlaying();
        } else {
            resumePlaying();
        }
    }

    private void startPlaying() {
        mPlayButton.setImageResource(R.drawable.ic_media_pause);
        mMediaPlayer = new MediaPlayer();

        try {
            mMediaPlayer.setDataSource(item.getFilePath());
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.prepare();
            mSeekBar.setMax(mMediaPlayer.getDuration());
            requestAudioFocus();

            mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mMediaPlayer.start();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            EventBroadcaster.send(getContext(), R.string.error_prepare_playback);
        }

        mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                stopPlaying();
            }
        });

        updateSeekBar();
        ScreenLock.keepScreenOn(getActivity());
    }

    private void prepareMediaPlayerFromPoint(int progress) {

        mMediaPlayer = new MediaPlayer();

        try {
            mMediaPlayer.setDataSource(item.getFilePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                final AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build();
                mMediaPlayer.setAudioAttributes(attributes);
            } else {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            mMediaPlayer.prepare();
            mSeekBar.setMax(mMediaPlayer.getDuration());
            mMediaPlayer.seekTo(progress);
            requestAudioFocus();

            mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopPlaying();
                }
            });

        } catch (IOException e) {
            e.printStackTrace();
        }

        ScreenLock.keepScreenOn(getActivity());
    }

    private void pausePlaying() {
        Log.i(LOG_TAG, "pausePlaying(), isPlaying = " + isPlaying);
        mResumeOnFocusGain = false;
        if (!isPlaying)
            return;

        mPlayButton.setImageResource(R.drawable.ic_media_play);
        mHandler.removeCallbacks(mRunnable);
        if (mMediaPlayer != null) {
            mMediaPlayer.pause();
        } else {
            Log.wtf(LOG_TAG, "mMediaPlayer is null");
        }
    }

    private void resumePlaying() {
        Log.i(LOG_TAG, "resumePlaying(), isPlaying = " + isPlaying);
        if (isPlaying)
            return;

        mPlayButton.setImageResource(R.drawable.ic_media_pause);
        mHandler.removeCallbacks(mRunnable);
        if (mMediaPlayer != null) {
            if (requestAudioFocus())
                mMediaPlayer.start();
        } else {
            Log.wtf(LOG_TAG, "mMediaPlayer is null");
        }
        updateSeekBar();
    }

    private void stopPlaying() {
        Log.i(LOG_TAG, "stopPlaying()");
        if (mMediaPlayer == null)
            return;

        mPlayButton.setImageResource(R.drawable.ic_media_play);
        mHandler.removeCallbacks(mRunnable);
        mMediaPlayer.stop();
        mMediaPlayer.reset();
        mMediaPlayer.release();
        mMediaPlayer = null;

        abandonAudioFocus();

        isPlaying = false;

        mCurrentProgressTextView.setText(mFileLengthTextView.getText());
        mSeekBar.setProgress(mSeekBar.getMax());

        ScreenLock.allowScreenTurnOff(getActivity());
    }

    private boolean requestAudioFocus() {
        if (!mFocused && getContext() != null) {
            mFocused = AudioManagerCompat.getInstance(getContext())
                    .requestAudioFocus(focusChangeListener,
                            AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN);
        }

        return mFocused;
    }

    private int abandonAudioFocus() {
        final int result;

        final Context context = getContext();
        if (context == null) {
            Log.wtf(LOG_TAG, "abandonAudioFocus(): getContext() returned null.");
            result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        } else {
            result = AudioManagerCompat.getInstance(context).abandonAudioFocus(focusChangeListener);
        }
        mFocused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

        return result;
    }

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            Log.d(LOG_TAG, "AudioManager.OnAudioFocusChangeListener#onAudioFocusChange " + focusChange);

            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    Log.i(LOG_TAG, "AudioManager.AUDIOFOCUS_LOSS: Pausing playback.");
                    mFocused = false;
                    pausePlaying();
                    isPlaying = false;
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    Log.i(LOG_TAG, "AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: Pausing playback.");
                    boolean resume = isPlaying || mResumeOnFocusGain;
                    mFocused = false;
                    pausePlaying();
                    isPlaying = false;
                    mResumeOnFocusGain = resume;
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Log.i(LOG_TAG, "AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Letting system duck.");
                    } else {
                        Log.i(LOG_TAG, "AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Ducking.");
                        if (mMediaPlayer != null) {
                            mMediaPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME);
                        }
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.i(LOG_TAG, "AudioManager.AUDIOFOCUS_GAIN: Increasing volume");
                    mMediaPlayer.setVolume(1f, 1f);
                    if (mResumeOnFocusGain) {
                        resumePlaying();
                        isPlaying = true;
                    }
                    mResumeOnFocusGain = false;
                    break;
                default:
                    Log.i(LOG_TAG, "Ignoring AudioFocus state change");
                    break;
            }
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            if (mMediaPlayer != null) {
                int currentPosition = mMediaPlayer.getCurrentPosition();
                mSeekBar.setProgress(currentPosition);
                mCurrentProgressTextView.setText(TimeUtils.formatDuration(currentPosition));
                updateSeekBar();
            }
        }
    };

    private void updateSeekBar() {
        mHandler.postDelayed(mRunnable, 1000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startOrResumePlaying();
                } else {
                    EventBroadcaster.send(getContext(),
                            R.string.error_no_permission_granted_for_playback);
                }
                break;
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(ARG_ITEM, item);
    }

}
