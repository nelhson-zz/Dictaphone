package org.borisveriga.soundrecorder.activities;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.snackbar.Snackbar;

import org.borisveriga.soundrecorder.R;
import org.borisveriga.soundrecorder.fragments.FileViewerFragment;
import org.borisveriga.soundrecorder.fragments.RecordFragment;
import org.borisveriga.soundrecorder.util.EventBroadcaster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private BroadcastReceiver mMessageReceiver = null;
    public static final List<String> REQUEST_INTENTS = Collections.singletonList(MediaStore.Audio.Media.RECORD_SOUND_ACTION);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        final ViewPager pager = findViewById(R.id.pager);
        setupViewPager(pager);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
        }

        final View root = findViewById(R.id.main_activity);
        mMessageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String message = intent.getStringExtra(EventBroadcaster.MESSAGE);
                Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
            }
        };

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (REQUEST_INTENTS.contains(getIntent().getAction())) {
            setResult(Activity.RESULT_CANCELED, null);
            finish();
        }
    }

    private static final int PAGER_SIZE = 2;
    private void setupViewPager(ViewPager viewPager) {
        final MyAdapter adapter = new MyAdapter(getSupportFragmentManager(), PAGER_SIZE);
        adapter.addFragment(RecordFragment.newInstance(), getString(R.string.tab_title_record));
        adapter.addFragment(FileViewerFragment.newInstance(), getString(R.string.tab_title_saved_recordings));
        viewPager.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver,
                new IntentFilter(EventBroadcaster.SHOW_SNACKBAR)
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
        } catch (Exception exc) {
            Log.e(LOG_TAG, "Error unregistering MessageReceiver", exc);
        }
    }


    public static class MyAdapter extends FragmentPagerAdapter {
        private final List<Fragment> fragments = new ArrayList<>();
        private final List<String> titles = new ArrayList<>();


        MyAdapter(FragmentManager fm, int pagesCount) {
            super(fm, pagesCount);
        }


        @NonNull
        @Override
        public Fragment getItem(int position) {
            return fragments.get(position);
        }

        @Override
        public int getCount() {
            return titles.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return titles.get(position);
        }

        void addFragment(Fragment fragment, String title) {
            fragments.add(fragment);
            titles.add(title);
        }

    }

}
