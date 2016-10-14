package org.onebillion.xprz.utils;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;

import org.onebillion.xprz.mainui.MainActivity;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by pedroloureiro on 30/08/16.
 */
public class OBBrightnessManager
{
    private long checkInterval;
    private boolean usesBrightnessAdjustment;
    //
    public static OBBrightnessManager sharedManager;
    private long lastTouchTimeStamp;
    private float lastBrightness;
    private boolean paused, suspended;
    private Runnable brightnessCheckRunnable;

    public OBBrightnessManager ()
    {
        sharedManager = this;
        lastTouchTimeStamp = System.currentTimeMillis();
        lastBrightness = 0f;
        paused = suspended = false;
    }


    public static void setBrightness (final float value)
    {
        OBUtils.runOnMainThread(new OBUtils.RunLambda()
        {
            @Override
            public void run () throws Exception
            {
                try
                {
//                    MainActivity.log("setBrightness (has write settings permission --> " + OBSystemsManager.sharedManager.hasWriteSettingsPermission() + ")");
                    int valueForSettings = Math.round(value * 255);
                    if (OBSystemsManager.sharedManager.hasWriteSettingsPermission())
                    {
                        Settings.System.putInt(MainActivity.mainActivity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, valueForSettings);
                        Settings.System.putInt(MainActivity.mainActivity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                        WindowManager.LayoutParams layoutpars = MainActivity.mainActivity.getWindow().getAttributes();
                        layoutpars.screenBrightness = value;
                        MainActivity.mainActivity.getWindow().setAttributes(layoutpars);
                        OBSystemsManager.sharedManager.refreshStatus();
                        MainActivity.log("Brightness has been set to: " + value + " --> " + valueForSettings);
                    }
                }
                catch (Exception e)
                {
//                    e.printStackTrace();
                }
            }
        });
    }

    public float maxBrightness()
    {
        String maxBrightnessString = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_MAX_BRIGHTNESS);
        float value = 1.0f;
        if (maxBrightnessString != null) value = Float.parseFloat(maxBrightnessString);
        return value;
    }



    public void runBrightnessCheck ()
    {
        if (suspended) return;
        //
        String brightnessInterval = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_BRIGHTNESS_CHECK_INTERVAL);
        checkInterval = 5;
        if (brightnessInterval != null) checkInterval = Long.parseLong(brightnessInterval);
        //
        String usesBrightnessAdjustmentString = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_USES_BRIGHTNESS_ADJUSTMENT);
        usesBrightnessAdjustment = (usesBrightnessAdjustmentString != null && usesBrightnessAdjustmentString.equals("true"));
        //
        if (!usesBrightnessAdjustment)
        {
            disableBrightnessAdjustment();
        }
        else
        {
            if (brightnessCheckRunnable == null)
            {
                final long interval = checkInterval * 1000;
                brightnessCheckRunnable = new Runnable()
                {
                    public void run ()
                    {
                        if (updateBrightness(true))
                        {
                            OBSystemsManager.sharedManager.mainHandler.removeCallbacks(brightnessCheckRunnable);
                            OBSystemsManager.sharedManager.mainHandler.postDelayed(this, interval);
                        }
                        else
                        {
//                        MainActivity.log("Brightness checker was paused");
                        }
                    }
                };
            }
            //
            if (OBSystemsManager.sharedManager.mainHandler != null && brightnessCheckRunnable != null)
            {
                OBSystemsManager.sharedManager.mainHandler.removeCallbacks(brightnessCheckRunnable);
                OBSystemsManager.sharedManager.mainHandler.post(brightnessCheckRunnable);
            }
        }
    }


    public String printStatus ()
    {
        WindowManager.LayoutParams layoutpars = MainActivity.mainActivity.getWindow().getAttributes();
        float brightness = Math.abs(layoutpars.screenBrightness);
        String result = String.format("%.1f%%", brightness * 100);
        return result;
    }


    public void registeredTouchOnScreen ()
    {
        if (usesBrightnessAdjustment)
        {
            OBUtils.runOnOtherThread(new OBUtils.RunLambda()
            {
                @Override
                public void run () throws Exception
                {
                    lastTouchTimeStamp = System.currentTimeMillis();
                    runBrightnessCheck();
                }
            });
        }
    }


    public boolean updateBrightness (boolean loop)
    {
        if (!usesBrightnessAdjustment)
        {
            setScreenSleepTimeToMax();
            setBrightness(maxBrightness());
            return false;
        }
        if (suspended) return false;
        //
        long currentTimeStamp = System.currentTimeMillis();
        long elapsed = currentTimeStamp - lastTouchTimeStamp;
        float percentage = (elapsed < checkInterval) ? maxBrightness() : (elapsed < checkInterval * 2) ? maxBrightness() / 2.0f : (elapsed < checkInterval * 3) ? maxBrightness() / 4.0f : 0.0f;
        //
//        MainActivity.log("updateBrightness : " + elapsed + " " + percentage);
        //
        if (lastBrightness != percentage)
        {
            lastBrightness = percentage;
            setBrightness(percentage);
            //
            if (percentage == maxBrightness())
            {
                setScreenSleepTimeToMax();
            }
            else if (percentage == 0.0f)
            {
                setScreenSleepTimeToMin();
            }
        }
        return loop && !paused && percentage > 0.0f;
    }


    public void onSuspend ()
    {
        MainActivity.log("OBBrightnessManager.onSuspend detected");
        suspended = true;
        setBrightness(maxBrightness());
    }


    public void onContinue ()
    {
//        MainActivity.log("OBBrightnessManager.onContinue detected");
        lastTouchTimeStamp = System.currentTimeMillis();
        suspended = false;
        runBrightnessCheck();
    }


    public void onResume ()
    {
//        MainActivity.log("OBBrightnessManager.onResume detected");
        lastTouchTimeStamp = System.currentTimeMillis();
        paused = false;
        MainActivity.log("OBBrightnessManager.onResume --> restoring brightnessCheckRunnable to Handler");
        runBrightnessCheck();
    }


    public void onPause ()
    {
//        MainActivity.log("OBBrightnessManager.onPause detected");
        paused = true;
        if (OBSystemsManager.sharedManager.mainHandler != null && brightnessCheckRunnable != null)
        {
            MainActivity.log("OBBrightnessManager.onPause --> removing brightnessCheckRunnable from Handler");
            OBSystemsManager.sharedManager.mainHandler.removeCallbacks(brightnessCheckRunnable);
        }
    }


    public void onStop ()
    {
//        MainActivity.log("OBBrightnessManager.onStop detected");
    }


    public void disableBrightnessAdjustment()
    {
        OBUtils.runOnMainThread(new OBUtils.RunLambda()
        {
            @Override
            public void run () throws Exception
            {
                MainActivity.log("OBBrightnessManager.disabling brightness adjustment");
                MainActivity.mainActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                MainActivity.mainActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
                if (OBSystemsManager.sharedManager.hasWriteSettingsPermission())
                {
                    int one_minute = 1000 * 60;
                    int valueForSettings = Math.round(maxBrightness() * 255);
                    Settings.System.putInt(MainActivity.mainActivity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, valueForSettings);
                    Settings.System.putInt(MainActivity.mainActivity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                    Settings.System.putInt(MainActivity.mainActivity.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, one_minute);
                    WindowManager.LayoutParams layoutpars = MainActivity.mainActivity.getWindow().getAttributes();
                    layoutpars.screenBrightness = maxBrightness();
                    MainActivity.mainActivity.getWindow().setAttributes(layoutpars);
                    OBSystemsManager.sharedManager.refreshStatus();
                }
                else
                {
                    MainActivity.log("OBBrightnessManager.does not have write settings permission. unable to revert changes for brightness management");
                }
            }
        });
    }


    public void setScreenTimeout (int millisecs)
    {
        MainActivity.log("OBBrightnessManager.setScreenTimeout: " + millisecs);
        try
        {
            if (OBSystemsManager.sharedManager.hasWriteSettingsPermission())
            {
                Settings.System.putInt(MainActivity.mainActivity.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, millisecs);
            }
            else
            {
                MainActivity.log("OBBrightnessManager.setScreenTimeout: Application does not have write settings permission");
            }
        }
        catch (Exception e)
        {
            MainActivity.log("OBBrightnessManager.setScreenTimeout: exception caught");
            e.printStackTrace();
        }
    }

    public void setScreenSleepTimeToMax()
    {
        MainActivity.log("OBBrightnessManager.setScreenSleepTimeToMax");
        //
        MainActivity.mainActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.mainActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        //
        String maxTimeString = MainActivity.mainActivity.configStringForKey(MainActivity.CONFIG_SCREEN_MAX_TIMEOUT);
        int maxTime = 60000; // 1 minute
        if (maxTimeString != null) maxTime = Integer.parseInt(maxTimeString) * 1000;
        //
        setScreenTimeout(maxTime);
    }


    public void setScreenSleepTimeToMin ()
    {
        MainActivity.log("OBBrightnessManager.setScreenSleepTimeToMin");
        //
        MainActivity.mainActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.mainActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        //
        setScreenTimeout(1);
    }

}
