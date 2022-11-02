package com.example.yang.videotranscoding;

import android.app.Application;

import com.squareup.leakcanary.LeakCanary;

/**
 * Created by admin on 2018/1/17.
 */

public class OApplication extends Application{

    @Override
    public void onCreate() {
        super.onCreate();
        /*if (LeakCanary.isInAnalyzerProcess(this)) {
            // This process is dedicated to LeakCanary for heap analysis.
            // You should not init your app in this process.
            return;
        }
        LeakCanary.install(this);*/
    }
}
