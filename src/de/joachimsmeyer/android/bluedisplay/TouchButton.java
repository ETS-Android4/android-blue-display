/*
 *     SUMMARY
 *     Blue Display is an Open Source Android remote Display for Arduino etc.
 *     It receives basic draw requests from Arduino etc. over Bluetooth and renders it.
 *     It also implements basic GUI elements as buttons and sliders.
 *     It sends touch or GUI callback events over Bluetooth back to Arduino.
 * 
 *  Copyright (C) 2014  Armin Joachimsmeyer
 *  armin.joachimsmeyer@gmail.com
 *  
 *     This file is part of BlueDisplay.
 *  BlueDisplay is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.

 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.

 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/gpl.html>.
 *  
 *  
 * This class implements simple touch buttons compatible with the one available as c++ code for arduino.
 * Usage of the java buttons reduces data sent over bluetooth as well as arduino program size.
 */

package de.joachimsmeyer.android.bluedisplay;

import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class TouchButton {

    // Logging
    private static final String LOG_TAG = "BT";

    RPCView mRPCView;
    int mButtonColor;
    int mPositionX;
    int mPositionY;
    int mCaptionPositionX;
    int mCaptionPositionY;
    int mWidth;
    int mHeight;

    int mCaptionSize;
    int mCaptionColor;
    String mRawCaption; // contains the caption as sent
    String mEscapedCaption; // contains the caption for Logging e.g. with \n replaced by "|"
    String[] mCaptionStrings; // contains the array of caption strings to implement multiline Captions

    int mValue;
    int mListIndex; // index in sButtonList
    int mCallbackAddress;
    boolean mDoBeep;
    boolean mIsManualRefresh; // default = false, true = no automatic refresh (currently only for red/green buttons)
    boolean mIsRedGreen; // Red = false, true = green
    String mRawCaptionForValueFalse; // contains the string for RedGreen button value == false
    String mRawCaptionForValueTrue; // contains the string for RedGreen button value == true

    /*
     * Autorepeat stuff
     */
    boolean mIsAutorepeatButton;
    int mMillisFirstAutorepeatDelay;
    int mMillisFirstAutorepeatRate;
    int mFirstAutorepeatCount;
    int mMillisSecondAutorepeatRate;
    // For timer
    static int sAutorepeatState;
    private static final int BUTTON_AUTOREPEAT_FIRST_PERIOD = 1;
    private static final int BUTTON_AUTOREPEAT_SECOND_PERIOD = 2;
    static int sAutorepeatCount;
    // static int sMillisFirstAutorepeatRate;
    // static int sMillisSecondAutorepeatRate;

    boolean mIsActive;
    boolean mIsInitialized;
    static int sTouchBeepIndex = ToneGenerator.TONE_CDMA_KEYPAD_VOLUME_KEY_LITE;
    static ToneGenerator sButtonToneGenerator;
    static int sLastRequestedToneVolume;
    static int sLastSystemToneVolume;
    static int sCurrentToneDurationMillis = -1; // -1 means till end of tone or forever

    static int sDefaultButtonColor = Color.RED;
    static int sDefaultCaptionColor = Color.BLACK;

    private static final int BUTTON_INITIAL_LIST_SIZE = 40;

    public static List<TouchButton> sButtonList = new ArrayList<TouchButton>(BUTTON_INITIAL_LIST_SIZE);

    private static final int FUNCTION_BUTTON_DRAW = 0x40;
    private static final int FUNCTION_BUTTON_DRAW_CAPTION = 0x41;
    private static final int FUNCTION_BUTTON_SETTINGS = 0x42;
    private static final int FUNCTION_BUTTON_REMOVE = 0x43;

    // static functions
    private static final int FUNCTION_BUTTON_ACTIVATE_ALL = 0x48;
    private static final int FUNCTION_BUTTON_DEACTIVATE_ALL = 0x49;
    private static final int FUNCTION_BUTTON_GLOBAL_SETTINGS = 0x4A;
    // Flags for BUTTON_GLOBAL_SETTINGS
    private static final int FLAG_BUTTON_GLOBAL_USE_UP_EVENTS_FOR_BUTTONS = 0x01;
    private static final int FLAG_BUTTON_GLOBAL_SET_BEEP_TONE = 0x02;

    // Function with variable data size
    private static final int FUNCTION_BUTTON_INIT = 0x70;
    // Flags for button settings contained in flags parameter
    private static final int FLAG_BUTTON_DO_BEEP_ON_TOUCH = 0x01;
    // Red if value == 0 else green
    private static final int FLAG_BUTTON_TYPE_TOGGLE_RED_GREEN = 0x02;
    private static final int FLAG_BUTTON_TYPE_AUTOREPEAT = 0x04;
    private static final int BUTTON_FLAG_MANUAL_REFRESH = 0x08;

    private static final int FUNCTION_BUTTON_SET_CAPTION_FOR_VALUE_TRUE = 0x71;
    private static final int FUNCTION_BUTTON_SET_CAPTION = 0x72;
    private static final int FUNCTION_BUTTON_SET_CAPTION_AND_DRAW_BUTTON = 0x73;

    // Flags for BUTTON_SETTINGS
    private static final int SUBFUNCTION_BUTTON_SET_BUTTON_COLOR = 0x00;
    private static final int SUBFUNCTION_BUTTON_SET_BUTTON_COLOR_AND_DRAW = 0x01;
    private static final int SUBFUNCTION_BUTTON_SET_CAPTION_COLOR = 0x02;
    private static final int SUBFUNCTION_BUTTON_SET_CAPTION_COLOR_AND_DRAW = 0x03;
    private static final int SUBFUNCTION_BUTTON_SET_VALUE = 0x04;
    private static final int SUBFUNCTION_BUTTON_SET_VALUE_AND_DRAW = 0x05;
    private static final int SUBFUNCTION_BUTTON_SET_COLOR_AND_VALUE = 0x06;
    private static final int SUBFUNCTION_BUTTON_SET_COLOR_AND_VALUE_AND_DRAW = 0x07;
    private static final int SUBFUNCTION_BUTTON_SET_POSITION = 0x08;
    private static final int SUBFUNCTION_BUTTON_SET_POSITION_AND_DRAW = 0x09;

    private static final int SUBFUNCTION_BUTTON_SET_ACTIVE = 0x10;
    private static final int SUBFUNCTION_BUTTON_RESET_ACTIVE = 0x11;
    private static final int SUBFUNCTION_BUTTON_SET_AUTOREPEAT_TIMING = 0x12;

    private static final int SUBFUNCTION_BUTTON_SET_CALLBACK = 0x20;

    TouchButton() {
        // empty button
        mIsInitialized = false;
    }

    /**
     * Static convenience method - reset all button lists and button flags
     */
    static void resetButtons(final RPCView aRPCView) {
        sButtonList.clear();
        aRPCView.mUseUpEventForButtons = false;
        sTouchBeepIndex = ToneGenerator.TONE_CDMA_KEYPAD_VOLUME_KEY_LITE;
    }

    /*
     * Old pre 3.2.5 version
     */
    void initButton(final RPCView aRPCView, final int aPositionX, final int aPositionY, final int aWidthX, final int aHeightY,
            final int aButtonColor, final String aCaption, final int aCaptionSizeAndFlags, final int aValue,
            final int aCallbackAddress) {
        initButton(aRPCView, aPositionX, aPositionY, aWidthX, aHeightY, aButtonColor, aCaption, aCaptionSizeAndFlags & 0xFF,
                aCaptionSizeAndFlags >> 8, aValue, aCallbackAddress);
    }

    void initButton(final RPCView aRPCView, final int aPositionX, final int aPositionY, final int aWidthX, final int aHeightY,
            final int aButtonColor, final String aCaption, final int aCaptionSize, final int aFlags, final int aValue,
            final int aCallbackAddress) {
        mRPCView = aRPCView;

        mWidth = aWidthX;
        mHeight = aHeightY;
        // Plausi is also done here
        setPosition(aPositionX, aPositionY);

        mButtonColor = aButtonColor;
        mValue = aValue;
        mCallbackAddress = aCallbackAddress;

        /*
         * Caption
         */
        mCaptionColor = sDefaultCaptionColor;
        mCaptionSize = aCaptionSize;
        handleCaption(aCaption);

        if (aButtonColor == 0) {
            mButtonColor = sDefaultButtonColor;
        }

        /*
         * local flags
         */
        int tFlags = aFlags;
        mDoBeep = false;
        mIsRedGreen = false;
        mIsManualRefresh = false;
        mIsAutorepeatButton = false;
        mMillisFirstAutorepeatDelay = 0;

        if ((tFlags & FLAG_BUTTON_DO_BEEP_ON_TOUCH) != 0) {
            if (sButtonToneGenerator == null) {
                int tCurrentSystemVolume = mRPCView.mBlueDisplayContext.mAudioManager.getStreamVolume(AudioManager.STREAM_SYSTEM);
                sButtonToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM,
                        (tCurrentSystemVolume * ToneGenerator.MAX_VOLUME) / mRPCView.mBlueDisplayContext.mMaxSystemVolume);
            }
            mDoBeep = true;
        }
        if ((tFlags & FLAG_BUTTON_TYPE_TOGGLE_RED_GREEN) != 0) {
            mRawCaptionForValueFalse = aCaption;
            mIsRedGreen = true;
            if (mValue != 0) {
                mValue = 1;
                mButtonColor = Color.GREEN;
            } else {
                mButtonColor = Color.RED;
            }
        }
        if ((tFlags & BUTTON_FLAG_MANUAL_REFRESH) != 0) {
            mIsManualRefresh = true;
        }

        if ((tFlags & FLAG_BUTTON_TYPE_AUTOREPEAT) != 0) {
            mIsAutorepeatButton = true;
        }

        mIsActive = false;
        mIsInitialized = true;
    }

    void drawButton() {
        mIsActive = true;
        // Draw rect
        mRPCView.fillRectRel(mPositionX, mPositionY, mWidth, mHeight, mButtonColor);
        drawCaption();
    }

    void removeButton(int tBackgroundColor) {
        // Clear rect
        mRPCView.fillRectRel(mPositionX, mPositionY, mWidth, mHeight, tBackgroundColor);
        mIsActive = false;
    }

    /*
     * Sets color and caption position
     */
    private void handleValueForRedGreenButton(int aValue) {
        if (mValue != 0) {
            // TRUE
            mValue = 1; // set to one instead of keeping any value != 0
            mButtonColor = Color.GREEN;
            if (mRawCaptionForValueTrue != null) {
                handleCaption(mRawCaptionForValueTrue);
            }
        } else {
            // FALSE
            mButtonColor = Color.RED;
            if (mRawCaptionForValueTrue != null) {
                // not needed if only one caption for both values
                handleCaption(mRawCaptionForValueFalse);
            }
        }
    }

    private void handleCaption(String aCaption) {
        mRawCaption = aCaption;
        mEscapedCaption = aCaption.replaceAll("\n", "|");
        mCaptionStrings = aCaption.split("\n");
        for (int i = 0; i < mCaptionStrings.length; i++) {
            // trim all strings
            mCaptionStrings[i] = mCaptionStrings[i].trim();
        }
        positionCaption();
    }

    private void positionCaption() {
        if (mCaptionSize > 0 && mEscapedCaption.length() >= 0) { // don't render anything if caption size == 0 or caption is empty
            if (mCaptionStrings.length > 1) {
                /*
                 * Multiline caption first position the string vertical
                 */
                if (mCaptionSize * mCaptionStrings.length >= mHeight) {
                    // Font height to big
                    MyLog.w(LOG_TAG, "caption\"" + mEscapedCaption + "\" with " + mCaptionStrings.length + " lines to high");
                    mCaptionPositionY = mPositionY + (int) ((RPCView.TEXT_ASCEND_FACTOR * mCaptionSize) + 0.5); // Fallback - start
                                                                                                                // at top + ascend
                } else {
                    mCaptionPositionY = (mPositionY + ((mHeight - mCaptionSize * (mCaptionStrings.length)) / 2) + (int) ((RPCView.TEXT_ASCEND_FACTOR * mCaptionSize) + 0.5));
                }
                mCaptionPositionX = -1; // to indicate multiline caption

            } else {
                /*
                 * Single line caption - just try to position the string in the middle of the box
                 */
                int tLength = (int) ((RPCView.TEXT_WIDTH_FACTOR * mCaptionSize * mEscapedCaption.length()) + 0.5);
                if (tLength >= mWidth) {
                    // String too long here
                    mCaptionPositionX = mPositionX;
                    MyLog.w(LOG_TAG, "caption\"" + mEscapedCaption + "\" to long");
                } else {
                    mCaptionPositionX = mPositionX + ((mWidth - tLength) / 2);
                }

                if (mCaptionSize >= mHeight) {
                    // Font height to big
                    MyLog.w(LOG_TAG, "caption\"" + mEscapedCaption + "\" to high");
                }
                // (RPCView.TEXT_ASCEND_FACTOR * mCaptionSize) is Ascend
                mCaptionPositionY = (int) ((mPositionY + ((mHeight - mCaptionSize) / 2) + (RPCView.TEXT_ASCEND_FACTOR * mCaptionSize)) + 0.5);
            }
        }
    }

    /**
     * draws the caption of a button
     */
    void drawCaption() {
        mIsActive = true;
        if (mCaptionSize > 0) { // don't render anything if caption size == 0
            if (mCaptionStrings.length == 1) {
                mRPCView.drawTextWithBackground(mCaptionPositionX, mCaptionPositionY, mCaptionStrings[0], mCaptionSize,
                        mCaptionColor, mButtonColor);
            } else {
                int TPosY = mCaptionPositionY;
                // Multiline caption
                for (int i = 0; i < mCaptionStrings.length; i++) {
                    // try to position the string in the middle of the box
                    int tLength = (int) ((RPCView.TEXT_WIDTH_FACTOR * mCaptionSize * mCaptionStrings[i].length()) + 0.5);
                    int tCaptionPositionX;
                    if (tLength >= mWidth) {
                        // String too long here
                        tCaptionPositionX = mPositionX;
                        MyLog.w(LOG_TAG, "sub caption\"" + mCaptionStrings[i] + "\" to long");
                    } else {
                        tCaptionPositionX = mPositionX + ((mWidth - tLength) / 2);
                    }
                    mRPCView.drawTextWithBackground(tCaptionPositionX, TPosY, mCaptionStrings[i], mCaptionSize, mCaptionColor,
                            mButtonColor);
                    TPosY += mCaptionSize + 1; // for space between lines otherwise we see "g" truncated
                }
            }
        }
    }

    void setPosition(int aPositionX, int aPositionY) {
        mPositionX = aPositionX;
        mPositionY = aPositionY;

        // check values
        if (aPositionX + mWidth > mRPCView.mRequestedCanvasWidth) {
            MyLog.e(LOG_TAG, mEscapedCaption + " Button x-position/width " + aPositionX + "+" + mWidth + " wrong. Set width to:"
                    + (mRPCView.mRequestedCanvasWidth - aPositionX));
            mWidth = mRPCView.mRequestedCanvasWidth - aPositionX;
        }
        if (aPositionY + mHeight > mRPCView.mRequestedCanvasHeight) {
            MyLog.e(LOG_TAG, mEscapedCaption + " Button y-position/height " + aPositionY + "+" + mHeight + " wrong. Set height to:"
                    + (mRPCView.mRequestedCanvasHeight - aPositionY));
            mHeight = mRPCView.mRequestedCanvasHeight - aPositionY;
        }
    }

    /**
     * Check if touch event is in button area. If yes - call callback function and return true if no - return false
     * 
     * @param aJustCheck
     * @return true if any active button touched
     */
    boolean checkIfTouchInButton(int aTouchPositionX, int aTouchPositionY, boolean aDoCallbackOnlyForAutorepeatButton) {
        if (mIsActive && mCallbackAddress != 0 && checkIfTouchInButtonArea(aTouchPositionX, aTouchPositionY)) {
            if (!aDoCallbackOnlyForAutorepeatButton || mIsAutorepeatButton) {
                /*
                 * Touch position is in button - call callback function
                 */
                if (mDoBeep) {
                    /*
                     * check if user changed volume
                     */
                    int tCurrentSystemVolume = mRPCView.mBlueDisplayContext.mAudioManager
                            .getStreamVolume(AudioManager.STREAM_SYSTEM);
                    if (sLastSystemToneVolume != tCurrentSystemVolume) {
                        sLastSystemToneVolume = tCurrentSystemVolume;
                        sButtonToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM,
                                (tCurrentSystemVolume * ToneGenerator.MAX_VOLUME) / mRPCView.mBlueDisplayContext.mMaxSystemVolume);
                    }
                    sButtonToneGenerator.startTone(sTouchBeepIndex, sCurrentToneDurationMillis);
                }
                /*
                 * Handle Toggle red/green button
                 */
                String tCaptionForInfo = mEscapedCaption;
                if (mIsRedGreen) {
                    /*
                     * Toggle value
                     */
                    if (mValue == 0) {
                        // TRUE
                        mValue = 1;
                    } else {
                        // FALSE
                        mValue = 0;
                    }
                    handleValueForRedGreenButton(mValue);
                    if (MyLog.isDEBUG()) {
                        MyLog.d(LOG_TAG, "Set value=" + mValue + " for" + " \"" + mEscapedCaption + "\". ButtonNr=" + mListIndex);
                    }
                    if (!mIsManualRefresh) {
                        drawButton();
                        // Trigger next frame in order to show changed button
                        mRPCView.invalidate();
                    }
                }

                mRPCView.mBlueDisplayContext.mSerialService.writeGuiCallbackEvent(SerialService.EVENT_BUTTON_CALLBACK, mListIndex,
                        mCallbackAddress, mValue, tCaptionForInfo);

                /*
                 * Handle autorepeat
                 */
                if (mIsAutorepeatButton) {
                    if (mMillisFirstAutorepeatDelay == 0) {
                        MyLog.w(LOG_TAG, "Autorepeat button " + mEscapedCaption + " without timing!");
                    } else {
                        sAutorepeatState = BUTTON_AUTOREPEAT_FIRST_PERIOD;
                        sAutorepeatCount = mFirstAutorepeatCount;
                        mAutorepeatHandler.sendEmptyMessageDelayed(0, mMillisFirstAutorepeatDelay);
                    }
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 
     * @param aTouchPositionX
     * @param aTouchPositionY
     * @param aJustCheck
     *            - do not call callback function of button
     * @return number of button if touched else -1
     */
    static int checkAllButtons(int aTouchPositionX, int aTouchPositionY, boolean aDoCallbackOnlyForAutorepeatButton) {
        // walk through list of active elements
        for (TouchButton tButton : sButtonList) {
            if (tButton.mIsActive
                    && tButton.checkIfTouchInButton(aTouchPositionX, aTouchPositionY, aDoCallbackOnlyForAutorepeatButton)) {
                return tButton.mListIndex;
            }
        }
        return -1;
    }

    /**
     * Static convenience method - activate all buttons
     */
    static void activateAllButtons() {
        for (TouchButton tButton : sButtonList) {
            if (tButton != null) {
                tButton.mIsActive = true;
            }
        }
    }

    /**
     * Static convenience method - deactivate all buttons (e.g. before switching screen)
     */
    static void deactivateAllButtons() {
        // check needed, because method is called also by setFlags()
        if (sButtonList != null && sButtonList.size() > 0) {
            for (TouchButton tButton : sButtonList) {
                if (tButton != null) {
                    tButton.mIsActive = false;
                }
            }
        }
    }

    /**
     * Check if touch event is in button area if yes - return true if no - return false
     */
    private boolean checkIfTouchInButtonArea(int aTouchPositionX, int aTouchPositionY) {
        if (aTouchPositionX < mPositionX || aTouchPositionX > mPositionX + mWidth || aTouchPositionY < mPositionY
                || aTouchPositionY > (mPositionY + mHeight)) {
            return false;
        }
        return true;
    }

    @SuppressLint("HandlerLeak")
    private final Handler mAutorepeatHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mRPCView.mTouchIsActive[0]) {
                // beep and send button event
                if (mDoBeep) {
                    sButtonToneGenerator.startTone(sTouchBeepIndex, sCurrentToneDurationMillis);
                }
                mRPCView.mBlueDisplayContext.mSerialService.writeGuiCallbackEvent(SerialService.EVENT_BUTTON_CALLBACK, mListIndex,
                        mCallbackAddress, mValue, mEscapedCaption);
                if (sAutorepeatState == BUTTON_AUTOREPEAT_FIRST_PERIOD) {
                    sAutorepeatCount--;
                    if (sAutorepeatCount <= 0) {
                        sAutorepeatState = BUTTON_AUTOREPEAT_SECOND_PERIOD;
                    }
                    sendEmptyMessageDelayed(0, mMillisFirstAutorepeatRate);
                } else {
                    sendEmptyMessageDelayed(0, mMillisSecondAutorepeatRate);
                }
            }
        }
    };

    public static void interpretCommand(final RPCView aRPCView, int aCommand, int[] aParameters, int aParamsLength,
            byte[] aDataBytes, int[] aDataInts, int aDataLength) {
        int tButtonNumber = -1; // to have it initialized ;-)
        TouchButton tButton = null;
        String tButtonCaption = "";
        String tString;

        /*
         * Plausi
         */
        if (aCommand != FUNCTION_BUTTON_ACTIVATE_ALL && aCommand != FUNCTION_BUTTON_DEACTIVATE_ALL
                && aCommand != FUNCTION_BUTTON_GLOBAL_SETTINGS) {
            /*
             * We need a button for the command
             */
            if (aParamsLength <= 0) {
                MyLog.e(LOG_TAG,
                        "aParamsLength is <=0 but Command=0x" + Integer.toHexString(aCommand) + " is not one of 0x"
                                + Integer.toHexString(FUNCTION_BUTTON_ACTIVATE_ALL) + ", 0x"
                                + Integer.toHexString(FUNCTION_BUTTON_DEACTIVATE_ALL) + " or 0x"
                                + Integer.toHexString(FUNCTION_BUTTON_GLOBAL_SETTINGS));
                return;
            } else {
                tButtonNumber = aParameters[0];
                if (tButtonNumber >= 0 && tButtonNumber < sButtonList.size()) {
                    // get button for create with existent button number
                    tButton = sButtonList.get(tButtonNumber);
                    if (aCommand != FUNCTION_BUTTON_INIT && (tButton == null || !tButton.mIsInitialized)) {
                        MyLog.e(LOG_TAG, "Command=0x" + Integer.toHexString(aCommand) + " ButtonNr=" + tButtonNumber
                                + " is null or not initialized.");
                        return;
                    }
                    if (MyLog.isINFO()) {
                        tButtonCaption = " \"" + tButton.mEscapedCaption + "\". ButtonNr=";
                    }
                } else if (aCommand != FUNCTION_BUTTON_INIT) {
                    MyLog.e(LOG_TAG, "Command=0x" + Integer.toHexString(aCommand) + " ButtonNr=" + tButtonNumber
                            + " not found. Only " + sButtonList.size() + " buttons created.");
                    return;
                }
            }
        }

        switch (aCommand) {

        case FUNCTION_BUTTON_ACTIVATE_ALL:
            if (MyLog.isINFO()) {
                MyLog.i(LOG_TAG, "Activate all buttons");
            }
            activateAllButtons();
            break;

        case FUNCTION_BUTTON_DEACTIVATE_ALL:
            if (MyLog.isINFO()) {
                MyLog.i(LOG_TAG, "Deactivate all buttons");
            }
            deactivateAllButtons();
            break;

        case FUNCTION_BUTTON_GLOBAL_SETTINGS:
            if ((aParameters[0] & FLAG_BUTTON_GLOBAL_USE_UP_EVENTS_FOR_BUTTONS) != 0) {
                if (aRPCView.mTouchIsActive[0] && !aRPCView.mUseUpEventForButtons) {
                    // since we switched mode while button was down
                    aRPCView.mDisableButtonUpOnce = true;
                }
                aRPCView.mUseUpEventForButtons = true;
            } else {
                aRPCView.mUseUpEventForButtons = false;
            }

            String tInfoString = "";
            if ((aParameters[0] & FLAG_BUTTON_GLOBAL_SET_BEEP_TONE) != 0) {
                if (aParamsLength > 1) {
                    // set Tone
                    if (aParamsLength > 2) {
                        /*
                         * set duration in ms
                         */
                        sCurrentToneDurationMillis = aParameters[2];
                        if (aParamsLength > 3) {
                            /*
                             * set absolute value of volume
                             */
                            if ((aParameters[3] >= 0 || aParameters[3] < ToneGenerator.MAX_VOLUME)
                                    && aParameters[3] != sLastRequestedToneVolume) {
                                sLastRequestedToneVolume = aParameters[3];
                                sButtonToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM, sLastRequestedToneVolume);
                            }
                        } else {
                            /*
                             * set to user defined value of volume
                             */
                            int tCurrentSystemVolume = aRPCView.mBlueDisplayContext.mAudioManager
                                    .getStreamVolume(AudioManager.STREAM_SYSTEM);
                            sButtonToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM,
                                    (tCurrentSystemVolume * ToneGenerator.MAX_VOLUME)
                                            / aRPCView.mBlueDisplayContext.mMaxSystemVolume);
                        }
                    }
                    if (aParameters[1] > 0 && aParameters[1] < ToneGenerator.TONE_CDMA_SIGNAL_OFF) {
                        sTouchBeepIndex = aParameters[1];
                    }
                    if (MyLog.isINFO()) {
                        tInfoString = " Touch tone volume=" + sLastRequestedToneVolume + ", index=" + sTouchBeepIndex;
                    }
                }
            }
            if (MyLog.isINFO()) {
                tString = "";
                if (aRPCView.mUseUpEventForButtons) {
                    tString = " UseUpEventForButtons=";
                }
                MyLog.i(LOG_TAG, "Global settings. Flags=0x" + Integer.toHexString(aParameters[0]) + tString
                        + aRPCView.mUseUpEventForButtons + "." + tInfoString);
            }
            break;

        case FUNCTION_BUTTON_DRAW:
            if (MyLog.isINFO()) {
                MyLog.i(LOG_TAG, "Draw button" + tButtonCaption + tButtonNumber);
            }
            tButton.drawButton();
            break;

        case FUNCTION_BUTTON_REMOVE:
            int tBackgroundColor = RPCView.shortToLongColor(aParameters[1]);
            if (MyLog.isINFO()) {
                MyLog.i(LOG_TAG, "Remove button background color= " + RPCView.shortToColorString(aParameters[1]) + " for"
                        + tButtonCaption + tButtonNumber);
            }
            tButton.removeButton(tBackgroundColor);
            break;

        case FUNCTION_BUTTON_DRAW_CAPTION:
            if (MyLog.isINFO()) {
                MyLog.i(LOG_TAG, "Draw caption=" + tButtonCaption + tButtonNumber);
            }
            tButton.drawCaption();
            break;

        case FUNCTION_BUTTON_SET_CAPTION:
        case FUNCTION_BUTTON_SET_CAPTION_AND_DRAW_BUTTON:
            aRPCView.myConvertChars(aDataBytes, RPCView.sCharsArray, aDataLength);
            tString = new String(RPCView.sCharsArray, 0, aDataLength);
            tButton.handleCaption(tString);

            if (MyLog.isINFO()) {
                MyLog.i(LOG_TAG, "Set caption=\"" + tButton.mEscapedCaption + "\" for" + tButtonCaption + tButtonNumber);
            }
            if (aCommand == FUNCTION_BUTTON_SET_CAPTION_AND_DRAW_BUTTON) {
                tButton.drawButton();
            }
            break;

        case FUNCTION_BUTTON_SET_CAPTION_FOR_VALUE_TRUE:
            aRPCView.myConvertChars(aDataBytes, RPCView.sCharsArray, aDataLength);
            tString = new String(RPCView.sCharsArray, 0, aDataLength);
            tButton.mRawCaptionForValueTrue = tString;
            String tEscapedCaption = tString.replaceAll("\n", "|");
            if (tButton.mValue != 0) {
                // set right caption position etc. if value is already true
                tButton.handleCaption(tString);
            }
            if (MyLog.isINFO()) {
                MyLog.i(LOG_TAG, "Set caption=\"" + tEscapedCaption + "\" for value true for" + tButtonCaption + tButtonNumber);
            }
            break;

        case FUNCTION_BUTTON_SETTINGS:
            int tSubcommand = aParameters[1];
            switch (tSubcommand) {
            case SUBFUNCTION_BUTTON_SET_BUTTON_COLOR:
            case SUBFUNCTION_BUTTON_SET_BUTTON_COLOR_AND_DRAW:
                tButton.mButtonColor = RPCView.shortToLongColor(aParameters[2]);
                if (MyLog.isINFO()) {
                    String tFunction = " for";
                    if (tSubcommand == SUBFUNCTION_BUTTON_SET_BUTTON_COLOR_AND_DRAW) {
                        tFunction = " and draw for";
                    }
                    MyLog.i(LOG_TAG, "Set button color= " + RPCView.shortToColorString(aParameters[2]) + tFunction + tButtonCaption
                            + tButtonNumber);

                }
                if (tSubcommand == SUBFUNCTION_BUTTON_SET_BUTTON_COLOR_AND_DRAW) {
                    tButton.drawButton();
                }
                break;

            case SUBFUNCTION_BUTTON_SET_CAPTION_COLOR_AND_DRAW:
            case SUBFUNCTION_BUTTON_SET_CAPTION_COLOR:
                tButton.mCaptionColor = RPCView.shortToLongColor(aParameters[2]);
                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG, "Set caption color= " + RPCView.shortToColorString(aParameters[2]) + " for" + tButtonCaption
                            + tButtonNumber);
                }
                if (tSubcommand == SUBFUNCTION_BUTTON_SET_CAPTION_COLOR_AND_DRAW) {
                    tButton.drawButton();
                }
                break;

            case SUBFUNCTION_BUTTON_SET_VALUE:
            case SUBFUNCTION_BUTTON_SET_VALUE_AND_DRAW:
                tButton.mValue = aParameters[2] & 0x0000FFFF;
                if (aParamsLength == 4) {
                    tButton.mValue = tButton.mValue | (aParameters[3] << 16);
                }
                if (tButton.mIsRedGreen) {
                    tButton.handleValueForRedGreenButton(tButton.mValue);
                }
                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG, "Set value=" + aParameters[2] + " for" + tButtonCaption + tButtonNumber);
                }
                if (tSubcommand == SUBFUNCTION_BUTTON_SET_VALUE_AND_DRAW) {
                    tButton.drawButton();
                }
                break;

            case SUBFUNCTION_BUTTON_SET_POSITION:
            case SUBFUNCTION_BUTTON_SET_POSITION_AND_DRAW:
                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG, "Set position=" + aParameters[2] + " / " + aParameters[3] + ". " + tButtonCaption
                            + tButtonNumber);
                }
                tButton.setPosition(aParameters[2], aParameters[3]);
                // set new caption position
                tButton.positionCaption();
                if (tSubcommand == SUBFUNCTION_BUTTON_SET_POSITION_AND_DRAW) {
                    tButton.drawButton();
                }
                break;

            case SUBFUNCTION_BUTTON_SET_COLOR_AND_VALUE:
            case SUBFUNCTION_BUTTON_SET_COLOR_AND_VALUE_AND_DRAW:
                tButton.mButtonColor = RPCView.shortToLongColor(aParameters[2]);
                tButton.mValue = aParameters[3];
                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG, "set color= " + RPCView.shortToColorString(aParameters[2]) + " and value=" + tButton.mValue
                            + " for" + tButtonCaption + tButtonNumber);
                }
                if (tSubcommand == SUBFUNCTION_BUTTON_SET_COLOR_AND_VALUE_AND_DRAW) {
                    tButton.drawButton();
                }
                break;

            case SUBFUNCTION_BUTTON_SET_ACTIVE:
                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG, "Set active=true for" + tButtonCaption + tButtonNumber);
                }
                tButton.mIsActive = true;
                break;
            case SUBFUNCTION_BUTTON_RESET_ACTIVE:
                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG, "Set active=false for" + tButtonCaption + tButtonNumber);
                }
                tButton.mIsActive = false;
                break;

            case SUBFUNCTION_BUTTON_SET_AUTOREPEAT_TIMING:
                if (tButton.mIsAutorepeatButton) {
                    if (MyLog.isINFO()) {
                        MyLog.i(LOG_TAG, "Set autorepeat timing. 1.delay=" + aParameters[2] + ", 1.rate=" + aParameters[3]
                                + ", 1.count=" + aParameters[4] + ", 2.rate=" + aParameters[5] + ". " + tButtonCaption
                                + tButtonNumber);
                    }
                    tButton.mMillisFirstAutorepeatDelay = aParameters[2];
                    tButton.mMillisFirstAutorepeatRate = aParameters[3];
                    tButton.mFirstAutorepeatCount = aParameters[4];
                    tButton.mMillisSecondAutorepeatRate = aParameters[5];
                } else {
                    MyLog.w(LOG_TAG, "Refused to set autorepeat timing for non autorepeat button. 1.delay=" + aParameters[2]
                            + ", 1.rate=" + aParameters[3] + ", 1.count=" + aParameters[4] + ", 2.rate=" + aParameters[5] + ". "
                            + tButtonCaption + tButtonNumber);
                }
                break;

            case SUBFUNCTION_BUTTON_SET_CALLBACK:
                // Output real Arduino function address, since function pointer on Arduino are address_of_function >> 1
                String tCallbackAddressStringAdjustedForClientDebugging = "";
                String tOldCallbackAddressStringAdjustedForClientDebugging = "";

                int tCallbackAddress = aParameters[2] & 0x0000FFFF;
                if (aParamsLength == 4) {
                    // 32 bit callback address
                    tCallbackAddress = tCallbackAddress | (aParameters[3] << 16);
                } else {
                    // 16 bit Arduino / AVR address
                    tCallbackAddressStringAdjustedForClientDebugging = "/0x" + Integer.toHexString(tCallbackAddress << 1);
                }
                tOldCallbackAddressStringAdjustedForClientDebugging = "/0x" + Integer.toHexString(tButton.mCallbackAddress << 1);

                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG,
                            "Set callback from 0x" + Integer.toHexString(tButton.mCallbackAddress)
                                    + tOldCallbackAddressStringAdjustedForClientDebugging + " to 0x"
                                    + Integer.toHexString(tCallbackAddress) + tCallbackAddressStringAdjustedForClientDebugging
                                    + ". " + tButtonCaption + tButtonNumber);
                }
                tButton.mCallbackAddress = tCallbackAddress;
                break;

            }
            break;

        case FUNCTION_BUTTON_INIT:
            aRPCView.myConvertChars(aDataBytes, RPCView.sCharsArray, aDataLength);
            tButtonCaption = new String(RPCView.sCharsArray, 0, aDataLength);
            int tCallbackAddress;
            String tCallbackAddressStringAdjustedForClientDebugging = "";
            if (aParamsLength == 9) {
                // 16 bit callback address
                // pre 3.2.5 parameters
                tCallbackAddress = aParameters[8] & 0x0000FFFF;
            } else {
                // 16 bit callback address
                tCallbackAddress = aParameters[9] & 0x0000FFFF;
            }
            if (aParamsLength == 11) {
                // 32 bit callback address
                tCallbackAddress = tCallbackAddress | (aParameters[10] << 16);
            } else {
                // Output real Arduino function address, since function pointer on Arduino are address_of_function >> 1
                // 16 bit Arduino / AVR address
                tCallbackAddressStringAdjustedForClientDebugging = "/avr=0x" + Integer.toHexString(tCallbackAddress << 1);
            }

            if (tButton == null) {
                /*
                 * create new button
                 */
                tButton = new TouchButton();
                if (tButtonNumber < sButtonList.size()) {
                    sButtonList.set(tButtonNumber, tButton);
                } else {
                    tButtonNumber = sButtonList.size();
                    sButtonList.add(tButton);
                    if (MyLog.isDEBUG()) {
                        Log.d(LOG_TAG, "Button with index " + tButtonNumber + " appended at end of list. List size now "
                                + sButtonList.size());
                    }
                }
                tButton.mListIndex = tButtonNumber;
            }
            if (aParamsLength == 9) {
                // pre 3.2.5 parameters
                tButton.initButton(aRPCView, aParameters[1], aParameters[2], aParameters[3], aParameters[4],
                        RPCView.shortToLongColor(aParameters[5]), tButtonCaption, aParameters[6], aParameters[7], tCallbackAddress);
                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG,
                            "Create button. ButtonNr=" + tButtonNumber + ", new TouchButton(\"" + tButton.mEscapedCaption
                                    + "\", x=" + aParameters[1] + ", y=" + aParameters[2] + ", width=" + aParameters[3]
                                    + ", height=" + aParameters[4] + ", color=" + RPCView.shortToColorString(aParameters[5])
                                    + ", flags=" + Integer.toHexString((aParameters[6] >> 8)) + ", size=" + (aParameters[6] & 0xFF)
                                    + ", value=" + aParameters[7] + ", callback=0x" + Integer.toHexString(tCallbackAddress)
                                    + tCallbackAddressStringAdjustedForClientDebugging + ") ListSize=" + sButtonList.size());
                }
            } else {
                // new Parameter set since version 3.2.5

                tButton.initButton(aRPCView, aParameters[1], aParameters[2], aParameters[3], aParameters[4],
                        RPCView.shortToLongColor(aParameters[5]), tButtonCaption, aParameters[6], aParameters[7], aParameters[8],
                        tCallbackAddress);

                if (MyLog.isINFO()) {
                    MyLog.i(LOG_TAG,
                            "Init button. ButtonNr=" + tButtonNumber + ", init(\"" + tButton.mEscapedCaption + "\", x="
                                    + aParameters[1] + ", y=" + aParameters[2] + ", width=" + aParameters[3] + ", height="
                                    + aParameters[4] + ", color=" + RPCView.shortToColorString(aParameters[5]) + ", size="
                                    + aParameters[6] + ", flags=" + Integer.toHexString(aParameters[7]) + ", value="
                                    + aParameters[8] + ", callback=0x" + Integer.toHexString(tCallbackAddress)
                                    + tCallbackAddressStringAdjustedForClientDebugging + ") ListSize=" + sButtonList.size());
                }
            }
            break;

        default:
            MyLog.e(LOG_TAG, "unknown command 0x" + Integer.toHexString(aCommand) + " received. paramsLength=" + aParamsLength
                    + " dataLenght=" + aDataLength);
            break;
        }
    }
}
