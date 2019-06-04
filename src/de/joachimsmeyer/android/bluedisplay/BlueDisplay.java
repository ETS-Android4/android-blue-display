/*
 * 	SUMMARY
 * 	Blue Display is an Open Source Android remote Display for Arduino etc.
 * 	It receives basic draw requests from Arduino etc. over Bluetooth and renders it.
 * 	It also implements basic GUI elements as buttons and sliders.
 * 	GUI callback, touch and sensor events are sent back to Arduino.
 * 
 *  Copyright (C) 2014  Armin Joachimsmeyer
 *  armin.joachimsmeyer@gmail.com
 *  
 *  This file is part of BlueDisplay.
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
 * FEATURES
 * Scale factor to enlarge the picture drawn.
 * Color in RGB 565.
 *  
 *  SUPPORTED FUNCTIONS:
 *  Set display size used for drawing commands. The real display size is user definable by just resizing the view.
 *  Set modes for Touch recognition.
 *  Clear display.
 *  Draw Pixel.
 *  Draw and fill Circle, Rectangle and Path.
 *  Draw Character, Text and Multi-line Text transparent or with background color for easy overwriting existent text.
 *  Draw Line.
 *  Draw Chart from byte or short values. Enables clearing of last drawn chart.
 *  Set Codepage and set Mapping for utf16 character to codepage location. I.e. have Omega at 0x81.
 *  
 */

package de.joachimsmeyer.android.bluedisplay;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class BlueDisplay extends Activity {

	// Intent request codes
	public static final int REQUEST_CONNECT_DEVICE = 1;
	private static final int REQUEST_ENABLE_BT = 2;

	// static boolean isDevelopmentTesting = true;
	static final String LOG_TAG = "BlueDisplay";

	// Message types sent from the BluetoothSerialService Handler
	public static final int MESSAGE_CHANGE_MENU_ITEM_FOR_CONNECTED_STATE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_AFTER_CONNECT = 4;
	public static final int MESSAGE_DISCONNECT = 5;
	public static final int MESSAGE_TOAST = 6;
	public static final int MESSAGE_UPDATE_VIEW = 7;

	// Message sent by RPCView
	public static final int REQUEST_INPUT_DATA = 10;
	public static final String CALLBACK_ADDRESS = "callback_address";
	public static final String DIALOG_PROMPT = "dialog_prompt";
	public static final String NUMBER_INITIAL_VALUE = "initialValue";
	public static final String NUMBER_FLAG = "doNumber";

	// Key names received from the BluetoothSerialService Handler
	public static final String TOAST = "toast";

	public static final String STATE_SCALE_FACTOR = "scale_factor";

	private BluetoothAdapter mBluetoothAdapter = null;

	/*
	 * Main view. Displays the remote data.
	 */
	protected RPCView mRPCView;

	Toast mMyToast;

	/*
	 * Bluetooth socket handler
	 */
	public BluetoothSerialService mSerialService = null;

	/*
	 * Sensor listener
	 */
	public Sensors mSensorEventListener;

	/*
	 * Audio manager for getting actual user volume setting
	 */
	public AudioManager mAudioManager;
	public int mMaxSystemVolume;

	private boolean mInTryToEnableEnableBT; // We try to enable Bluetooth
	private boolean mAutoConnectBT = false; // Auto connect at startup
	private String mAutoConnectMacAddressFromPreferences; // MAC address for auto connect at startup
	// to be displayed in preferences dialog
	private String mAutoConnectDeviceNameFromPreferences; // Device name for auto connect at startup
	private String mMacAddressToConnect; // MAC address for which an connect is tried
	private String mDeviceNameToConnect; // Device name for which an connect is tried
	private String mMacAddressConnected; // MAC address of the actual connected device
	// to be displayed in DeviceListActivity
	private String mDeviceNameConnected; // Device name of the actual connected device
	static final String BT_DEVICE_NAME = "bt_device_name";

	// State variable is declared in BluetoothSerialService
	private static final String SHOW_TOUCH_COORDINATES_KEY = "show_touch_mode";
	private static final String ALLOW_INSECURE_CONNECTIONS_KEY = "allowinsecureconnections";
	private static final String AUTO_CONNECT_KEY = "do_autoconnect";
	public static final String AUTO_CONNECT_MAC_ADDRESS_KEY = "autoconnect_mac_address";
	public static final String AUTO_CONNECT_DEVICE_NAME_KEY = "autoconnect_device_name";

	private static final String VERSION_KEY = "version";
	private static final String SCREENORIENTATION_KEY = "screenorientation";
	int mPreferredScreenOrientation;
	protected int mActualScreenOrientation;
	protected int mRequestedScreenOrientation;
	// rotation is 1 or 0 for landscape (usb connector right) depending on model
	protected int mActualRotation;
	protected boolean mOrientationisLockedByClient = false;
	protected String mActualScreenOrientationRotationString = "";

	MenuItem mMenuItemConnect;
	// private MenuItem mMenuItemStartStopLogging;

	private Dialog mAboutDialog;

	/******************
	 * Event Handler
	 ******************/
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "+++ ON CREATE +++");
		}

		/*
		 * Get Audio manager and max volume for beep volume handling in Buttons and playTone()
		 * Must be before create RPCView, since RPCView uses this values
		 */
		mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		mMaxSystemVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM);
		
		/*
		 * Create RPCView
		 */
		mRPCView = new RPCView(this, mHandlerBluetooth);
		setContentView(mRPCView);
		mRPCView.setFocusable(true);
		mRPCView.setFocusableInTouchMode(true);
		mRPCView.requestFocus();

		// Set default values only once after installation
		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
		// read preferences, needed for auto Bluetooth connection
		readPreferences();

		/*
		 * Bluetooth
		 */
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mBluetoothAdapter == null) {
			if (!MyLog.isVERBOSE()) {
				// finishDialogNoBluetooth();
			}

		} else {
			mSerialService = new BluetoothSerialService(this, mHandlerBluetooth);
			if (mSerialService.getState() == BluetoothSerialService.STATE_NONE && mBluetoothAdapter.isEnabled()) {
				if (mAutoConnectBT) {
					if (mAutoConnectMacAddressFromPreferences != null && mAutoConnectMacAddressFromPreferences.length() > 10) {
						mMacAddressToConnect = mAutoConnectMacAddressFromPreferences;
						// Get the BluetoothDevice object
						BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mMacAddressToConnect);
						// Attempt to connect to the device
						mSerialService.connect(device);
						mDeviceNameToConnect = device.getName();
					}
				} else {
					launchDeviceListActivity();
				}
			} else if (mSerialService.getState() == BluetoothSerialService.STATE_CONNECTED) {
				// stop running service
				mSerialService.stop();
			}
		}

		mInTryToEnableEnableBT = false;
		// mRPCView.showTestpage();

		/*
		 * List sensors
		 */
		mSensorEventListener = new Sensors(this, (SensorManager) getSystemService(Context.SENSOR_SERVICE));
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "+++ DONE IN ON CREATE +++");
		}

	}

	@Override
	public void onStart() {
		super.onStart();
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "++ ON START ++");
		}
	}

	@SuppressLint("InlinedApi")
	@Override
	public synchronized void onResume() {
		super.onResume();
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "+ ON RESUME +");
		}

		if (!mInTryToEnableEnableBT) {
			if ((mBluetoothAdapter != null) && (!mBluetoothAdapter.isEnabled())) {
				Log.i(LOG_TAG, "Activate bluetooth");
				Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
				mInTryToEnableEnableBT = true;
			}
		}
		boolean tOldAutoConnectBTValue = mAutoConnectBT;
		if (mOrientationisLockedByClient) {
			int tOldPreferredScreenOrientation = mPreferredScreenOrientation;
			readPreferences();
			mPreferredScreenOrientation = tOldPreferredScreenOrientation;
		} else {
			readPreferences();
			setActualScreenOrientation(mPreferredScreenOrientation);
		}
		if (mAutoConnectBT && !tOldAutoConnectBTValue && mSerialService != null
				&& mSerialService.getState() == BluetoothSerialService.STATE_CONNECTED) {
			writeStringPreference(AUTO_CONNECT_MAC_ADDRESS_KEY, mMacAddressToConnect);
			writeStringPreference(AUTO_CONNECT_DEVICE_NAME_KEY, mDeviceNameToConnect);
			mAutoConnectDeviceNameFromPreferences = mDeviceNameToConnect;
		}
		mMacAddressConnected = mMacAddressToConnect;
		mDeviceNameConnected = mDeviceNameToConnect;

		mActualRotation = getWindowManager().getDefaultDisplay().getRotation();
		mSensorEventListener.registerAllActiveSensorListeners();

		mRPCView.invalidate();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "+ onWindowFocusChanged focus=" + hasFocus);
		}
	}

	@Override
	public synchronized void onPause() {
		super.onPause();
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "- ON PAUSE -");
		}
		mSensorEventListener.deregisterAllActiveSensorListeners();
		mRPCView.mToneGenerator.stopTone();
	}

	@Override
	public void onStop() {
		super.onStop();
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "-- ON STOP --");
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "--- ON DESTROY ---");
		}
		if (mSerialService != null) {
			mSerialService.stop();
		}

	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		int tNewOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
		if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
			tNewOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
		} else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
			tNewOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
		} else {
			MyLog.w(LOG_TAG, "ON ConfigurationChanged new config is" + newConfig.orientation);
		}
		setActualScreenOrientationVariables(tNewOrientation);
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "--- ON ConfigurationChanged --- ");
		}
	}

	void setActualScreenOrientation(int aNewOrientation) {
		setActualScreenOrientationVariables(aNewOrientation);
		// really set orientation for device
		setRequestedOrientation(aNewOrientation);
		mRequestedScreenOrientation = aNewOrientation;
	}

	void setActualScreenOrientationVariables(int aNewOrientation) {
		mActualRotation = getWindowManager().getDefaultDisplay().getRotation();
		mActualScreenOrientation = aNewOrientation;
		/*
		 * Set the mActualScreenOrientationRotationString variable
		 */
		String tOrientation = "unknown";
		if (mActualScreenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
			tOrientation = "unspecified/auto";
		} else if (mActualScreenOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
			tOrientation = "landscape";
		} else if (mActualScreenOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
			tOrientation = "reverse landscape";
		} else if (mActualScreenOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
			tOrientation = "portrait";
		} else if (mActualScreenOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
			tOrientation = "reverse portrait";
		} else {
			MyLog.w(LOG_TAG, "Unknown orientation=" + aNewOrientation);
		}
		mActualScreenOrientationRotationString = tOrientation + "=" + aNewOrientation + " | " + (90 * mActualRotation) + " degrees";
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "Orientation is now: " + mActualScreenOrientationRotationString);
		}
	}

	/*
	 * Standard options menu
	 */
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		if (MyLog.isINFO()) {
			Log.i(LOG_TAG, "--- ON CreateOptionsMenu ---");
		}
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.option_menu, menu);
		mMenuItemConnect = menu.getItem(0);
		if (mMenuItemConnect != null) {
			if (mSerialService.getState() == BluetoothSerialService.STATE_CONNECTED) {
				// modify menu to show appropriate entry
				mMenuItemConnect.setIcon(android.R.drawable.ic_menu_close_clear_cancel);
				mMenuItemConnect.setTitle(R.string.menu_disconnect);
			} else {
				mMenuItemConnect.setIcon(android.R.drawable.ic_menu_search);
				mMenuItemConnect.setTitle(R.string.menu_connect);
			}
		}
		return true;
	}

	@SuppressLint("SimpleDateFormat")
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (MyLog.isVERBOSE()) {
			Log.v(LOG_TAG, "Item " + item.getTitle());
		}
		switch (item.getItemId()) {
		case R.id.menu_connect:
			if (mSerialService.getState() == BluetoothSerialService.STATE_NONE) {
				// connect request here
				launchDeviceListActivity();
			} else if (mSerialService.getState() == BluetoothSerialService.STATE_CONNECTED) {
				// Disconnect request here -> stop running service + reset locked orientation in turn
				// send disconnect message
				mSerialService.writeTwoIntegerEvent(BluetoothSerialService.EVENT_DISCONNECT, mRPCView.mActualViewWidth,
						mRPCView.mActualViewHeight);
				mSerialService.stop();
				BluetoothSerialService.sLastFailOrDisconnectTimestampMillis = System.currentTimeMillis();
			}
			return true;

		case R.id.menu_show_log:
			startActivity(new Intent(this, LogViewActivity.class));
			return true;

		case R.id.menu_preferences:
			Intent tPreferencesIntent = new Intent(this, BlueDisplayPreferences.class);
			tPreferencesIntent.putExtra(BT_DEVICE_NAME, mAutoConnectDeviceNameFromPreferences);
			startActivity(tPreferencesIntent);
			return true;

			// case R.id.menu_show_graph_testpage:
			// mRPCView.showGraphTestpage();
			// return true;

		case R.id.menu_show_font_testpage:
			mRPCView.showFontTestpage();
			return true;

		case R.id.menu_show_statistics:
			if (MyLog.isINFO()) {
				Log.i(LOG_TAG, mSerialService.getStatisticsString());
			}
			showStatisticsMessage();
			return true;
		case R.id.menu_about:
			showAboutDialog();
			return true;
		}
		return false;
	}

	/*****************
	 * OTHER HANDLER
	 *****************/
	/*
	 * The handler that gets information back from BluetoothSerialService
	 */
	@SuppressLint("HandlerLeak")
	private final Handler mHandlerBluetooth = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MESSAGE_CHANGE_MENU_ITEM_FOR_CONNECTED_STATE:
				/*
				 * called by SerialService -> manage the menu item
				 */
				if (MyLog.isVERBOSE()) {
					Log.v(LOG_TAG, "MESSAGE_CHANGE_MENU_ITEM_FOR_CONNECTED_STATE: " + msg.arg1);
				}
				switch (msg.arg1) {
				case BluetoothSerialService.STATE_CONNECTED:
					// modify menu to show disconnect entry
					if (mMenuItemConnect != null) {
						mMenuItemConnect.setIcon(android.R.drawable.ic_menu_close_clear_cancel);
						mMenuItemConnect.setTitle(R.string.menu_disconnect);
					}
					break;

				case BluetoothSerialService.STATE_CONNECTING:
					break;

				case BluetoothSerialService.STATE_NONE:
					if (mMenuItemConnect != null) {
						mMenuItemConnect.setIcon(android.R.drawable.ic_menu_search);
						mMenuItemConnect.setTitle(R.string.menu_connect);
					}
					break;
				}
				break;

			case MESSAGE_AFTER_CONNECT:
				/*
				 * called by SerialService after connect -> save the connected device's name,set window to always on, reset button
				 * and slider and show toast
				 */
				if (MyLog.isDEBUG()) {
					Log.d(LOG_TAG, "MESSAGE_AFTER_CONNECT: " + mDeviceNameConnected);
				}

				// set window to always on and reset all structures and flags
				getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

				mMyToast = Toast.makeText(getApplicationContext(), getString(R.string.toast_connected_to) + " "
						+ mDeviceNameConnected, Toast.LENGTH_SHORT);
				mMyToast.show();
				if (mAutoConnectBT) {
					writeStringPreference(AUTO_CONNECT_MAC_ADDRESS_KEY, mMacAddressToConnect);
					writeStringPreference(AUTO_CONNECT_DEVICE_NAME_KEY, mDeviceNameToConnect);
					mAutoConnectDeviceNameFromPreferences = mDeviceNameToConnect;
				}
				mMacAddressConnected = mMacAddressToConnect;
				mDeviceNameConnected = mDeviceNameToConnect;
				break;

			case MESSAGE_DISCONNECT:
				/*
				 * show toast, reset locked orientation, set window to normal (not persistent) state, stop tone, unregister sensor
				 * listener
				 */
				if (MyLog.isDEBUG()) {
					Log.d(LOG_TAG, "MESSAGE_DISCONNECT: " + mDeviceNameConnected);
				}
				Toast.makeText(getApplicationContext(), getString(R.string.toast_connection_lost) + " " + mDeviceNameConnected,
						Toast.LENGTH_SHORT).show();
				// reset eventually locked orientation
				mOrientationisLockedByClient = false;
				setActualScreenOrientation(mPreferredScreenOrientation);
				// set window to normal (not persistent) state
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				mRPCView.mToneGenerator.stopTone();
				mSensorEventListener.deregisterAllActiveSensorListeners();
				break;

			case MESSAGE_TOAST:
				/*
				 * only used by connectionFailed
				 */
				if (MyLog.isVERBOSE()) {
					Log.v(LOG_TAG, "MESSAGE_TOAST");
				}
				Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST), Toast.LENGTH_SHORT).show();
				break;

			case MESSAGE_UPDATE_VIEW:
				/*
				 * called by SerialService after command processed.
				 */
				if (MyLog.isVERBOSE()) {
					Log.v(LOG_TAG, "MESSAGE_UPDATE_VIEW");
				}
				mRPCView.invalidate();
				break;

			case REQUEST_INPUT_DATA:
				/*
				 * shows input data dialog (requested by FUNCTION_GET_NUMBER, FUNCTION_GET_TEXT etc.)
				 */
				if (MyLog.isVERBOSE()) {
					Log.v(LOG_TAG, "REQUEST_INPUT_DATA");
				}
				showInputDialog(msg.getData().getBoolean(NUMBER_FLAG), msg.getData().getInt(CALLBACK_ADDRESS), msg.getData()
						.getString(BlueDisplay.DIALOG_PROMPT), msg.getData().getFloat(NUMBER_INITIAL_VALUE));
				break;
			}

		}
	};

	/*
	 * Handles result from started activities
	 */
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (MyLog.isDEBUG()) {
			Log.d(LOG_TAG, "onActivityResult " + resultCode);
		}
		switch (requestCode) {

		case REQUEST_CONNECT_DEVICE:
			/*
			 * When DeviceListActivity returns with a device to connect
			 */
			if (resultCode == Activity.RESULT_OK) {
				// Get the device MAC address
				String tMacAddress = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
				if (tMacAddress.length() < 10) {
					// MAC Address not specified (no last device) chose a reasonable one
					if (mMacAddressConnected != null && mMacAddressConnected.length() > 10) {
						tMacAddress = mMacAddressConnected;
					} else if (mAutoConnectMacAddressFromPreferences != null && mAutoConnectMacAddressFromPreferences.length() > 10) {
						tMacAddress = mAutoConnectMacAddressFromPreferences;
					} else {
						mMyToast = Toast.makeText(getApplicationContext(), getString(R.string.toast_unable_to_connect)
								+ "  \"no device\"", Toast.LENGTH_SHORT);
						mMyToast.show();
						break;
					}
				}
				// Get the BluetoothDevice object
				BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(tMacAddress);
				// Attempt to connect to the device
				mSerialService.connect(device);
				mMacAddressToConnect = tMacAddress;
				mDeviceNameToConnect = device.getName();
			}
			break;

		case REQUEST_ENABLE_BT:
			/*
			 * When the request to enable Bluetooth returns
			 */
			if (resultCode != Activity.RESULT_OK) {
				if (MyLog.isDEBUG()) {
					Log.d(LOG_TAG, "BT not enabled");
				}
				finishDialogNoBluetooth();
			} else {
				launchDeviceListActivity();
			}
			break;

		}
	}

	void launchDeviceListActivity() {
		// Launch the DeviceListActivity to see devices
		Intent tDeviceListIntent = new Intent(this, DeviceListActivity.class);
		if (mDeviceNameConnected != null && mDeviceNameConnected.length() > 1) {
			tDeviceListIntent.putExtra(BT_DEVICE_NAME, mDeviceNameConnected);
		} else {
			tDeviceListIntent.putExtra(BT_DEVICE_NAME, mAutoConnectDeviceNameFromPreferences);
		}
		startActivityForResult(tDeviceListIntent, REQUEST_CONNECT_DEVICE);
	}

	/***********************
	 * DIALOG + MESSAGES
	 ***********************/

	/*
	 * Opens an alert, that Bluetooth is not available
	 */
	public void finishDialogNoBluetooth() {
		AlertDialog.Builder tBuilder = new AlertDialog.Builder(this);
		tBuilder.setMessage(R.string.alert_dialog_no_bt);
		tBuilder.setIcon(android.R.drawable.ic_dialog_info);
		tBuilder.setTitle(R.string.app_name);
		tBuilder.setCancelable(false);
		tBuilder.setPositiveButton(R.string.alert_dialog_ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				finish();
			}
		});
		AlertDialog tAlertDialog = tBuilder.create();
		tAlertDialog.show();
	}

	private void showAboutDialog() {
		mAboutDialog = new Dialog(BlueDisplay.this);
		mAboutDialog.setContentView(R.layout.about);
		mAboutDialog.setTitle(getString(R.string.app_name) + " " + getString(R.string.app_version));

		Button buttonOK = (Button) mAboutDialog.findViewById(R.id.buttonDialog);
		buttonOK.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				mAboutDialog.dismiss();
			}
		});
		mAboutDialog.show();
	}

	/*
	 * Show input dialog requested by client
	 */
	@SuppressLint("InflateParams")
	public void showInputDialog(boolean aDoNumber, final int aCallbackAddress, String tShortPrompt, float aInitialValue) {
		LayoutInflater tLayoutInflater = LayoutInflater.from(this);
		View tInputView = tLayoutInflater.inflate(R.layout.input_data, null);

		if (tShortPrompt != null && tShortPrompt.length() > 0) {
			final TextView tTitle = (TextView) tInputView.findViewById(R.id.title_input_data);
			String tPromptLeading = getResources().getString(R.string.title_input_data_prompt_leading);
			if (tPromptLeading.length() == 0 && tShortPrompt.length() > 1) {
				// convert first character to upper case
				tShortPrompt = Character.toUpperCase(tShortPrompt.charAt(0)) + tShortPrompt.substring(1);
			}
			String tPromptTrailing = getResources().getString(R.string.title_input_data_prompt_trailing);
			tTitle.setText(tPromptLeading + " " + tShortPrompt + " " + tPromptTrailing);
		}
		final boolean tDoNumber = aDoNumber;
		final EditText tUserInput = (EditText) tInputView.findViewById(R.id.editTextDialogUserInput);
		if (aDoNumber) {
			tUserInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
					| InputType.TYPE_NUMBER_FLAG_SIGNED);
		}
		if (aInitialValue != RPCView.NUMBER_INITIAL_VALUE_DO_NOT_SHOW) {
			tUserInput.setText(Float.toString(aInitialValue).replaceAll("\\.?0*$", ""));
		}
		AlertDialog.Builder tBuilder = new AlertDialog.Builder(this);
		tBuilder.setView(tInputView);
		tBuilder.setCancelable(true);
		tBuilder.setPositiveButton(R.string.alert_dialog_ok, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				if (tDoNumber) {
					Editable tNumber = tUserInput.getText();
					float tValue;
					try {
						tValue = Float.parseFloat(tNumber.toString());
						mSerialService.writeNumberCallbackEvent(BluetoothSerialService.EVENT_NUMBER_CALLBACK, aCallbackAddress, tValue);
					} catch (NumberFormatException e) {
						MyLog.i(LOG_TAG, "Entered data \"" + tNumber + "\" is no float. No value sent.");
					}
				} else {
					// getText function here - Not yet implemented
				}
			}
		});
		tBuilder.setNegativeButton(R.string.alert_dialog_cancel, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				dialog.cancel();
			}
		});
		AlertDialog tAlertDialog = tBuilder.create();
		tAlertDialog.show();
	}

	/*
	 * Opens an alert and show statistics
	 */
	public void showStatisticsMessage() {
		AlertDialog.Builder tBuilder = new AlertDialog.Builder(this);
		tBuilder.setMessage(mSerialService.getStatisticsString());
		tBuilder.setCancelable(false);
		tBuilder.setPositiveButton(R.string.alert_dialog_ok, new DialogInterface.OnClickListener() {
			// empty listener
			public void onClick(DialogInterface dialog, int id) {
			}
		});
		tBuilder.setNegativeButton(R.string.alert_dialog_reset, new DialogInterface.OnClickListener() {
			// empty listener
			public void onClick(DialogInterface dialog, int id) {
				mSerialService.resetStatistics();
			}
		});
		AlertDialog tAlertDialog = tBuilder.create();
		tAlertDialog.show();
	}

	/****************
	 * PREFERENCES
	 ****************/
	private void readPreferences() {
		SharedPreferences tSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		/*
		 * Load preferences
		 */

		// integer preferences do not work on Android. Values of <integer-array>
		// are always stored as Strings in HashMap
		mPreferredScreenOrientation = Integer.parseInt(tSharedPreferences.getString(SCREENORIENTATION_KEY, ""
				+ ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
		/*
		 * Get Version and compare it with actual
		 */
		PackageInfo tPackageInfo;
		int tActualVersion = 0;
		try {
			tPackageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
			tActualVersion = tPackageInfo.versionCode;

		} catch (NameNotFoundException e) {
			MyLog.e(LOG_TAG, "Exception getting versionCode: " + e.toString());
		}
		int tOldVersion = Integer.parseInt(tSharedPreferences.getString(VERSION_KEY, "0"));
		if (tOldVersion == 0) {
			/*
			 * For old version 0 adjust screen orientation once (in 3.2)
			 */
			if (mPreferredScreenOrientation == 2) {
				mPreferredScreenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
			} else if (mPreferredScreenOrientation == 0) {
				mPreferredScreenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
			}
			MyLog.i(LOG_TAG, "Adjusted screen orientation preferences once.");

			/*
			 * Adjustment done -> now save the actual version in preferences
			 */
			SharedPreferences.Editor tEdit = tSharedPreferences.edit();
			tEdit.putString(SCREENORIENTATION_KEY, Integer.toString(mPreferredScreenOrientation));
			tEdit.putString(VERSION_KEY, Integer.toString(tActualVersion));
			tEdit.commit();
		} else if (tOldVersion <= 12) {
			/*
			 * Introduction auto connect -> now save the actual version in preferences Adding the new strings is not really needed,
			 * but why not doing it here.
			 */
			SharedPreferences.Editor tEdit = tSharedPreferences.edit();
			tEdit.putBoolean(AUTO_CONNECT_KEY, false);
			tEdit.putString(AUTO_CONNECT_MAC_ADDRESS_KEY, "");
			tEdit.putString(AUTO_CONNECT_DEVICE_NAME_KEY, "");
			tEdit.putString(VERSION_KEY, Integer.toString(tActualVersion));
			tEdit.commit();
		}

		MyLog.setLoglevel(Integer.parseInt(tSharedPreferences.getString(MyLog.LOGLEVEL_KEY, Log.INFO + "")));

		if (mSerialService != null) {
			mSerialService.setAllowInsecureConnections(tSharedPreferences.getBoolean(ALLOW_INSECURE_CONNECTIONS_KEY,
					mSerialService.getAllowInsecureConnections()));
		}

		if (mRPCView != null) {
			// don't use setter methods since they modify the preference too
			// mRPCView.mTouchMoveEnable = tSharedPreferences.getBoolean(TOUCH_MOVE_KEY, mRPCView.isTouchMoveEnable());
			mRPCView.mShowTouchCoordinates = tSharedPreferences.getBoolean(SHOW_TOUCH_COORDINATES_KEY, false);
		}

		mAutoConnectBT = tSharedPreferences.getBoolean(AUTO_CONNECT_KEY, mAutoConnectBT);
		mAutoConnectMacAddressFromPreferences = tSharedPreferences.getString(AUTO_CONNECT_MAC_ADDRESS_KEY, "");
		mAutoConnectDeviceNameFromPreferences = tSharedPreferences.getString(AUTO_CONNECT_DEVICE_NAME_KEY, "");
	}

	private void writeStringPreference(String aKey, String aValue) {
		SharedPreferences tSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		String tOldValue = tSharedPreferences.getString(aKey, "");
		if (!tOldValue.equalsIgnoreCase(aValue)) {
			MyLog.i(LOG_TAG, "Write new preference value=\"" + aValue + "\" for key=" + aKey);
			SharedPreferences.Editor tEdit = tSharedPreferences.edit();
			tEdit.putString(aKey, aValue);
			tEdit.commit();
		}
	}

	@Override
	// Source is from http://stackoverflow.com/questions/9996333/openoptionsmenu-function-not-working-in-ics/17903128#17903128
	// I found the android one not working on a 5.0 tablet
	public void openOptionsMenu() {

		Configuration config = getResources().getConfiguration();

		if ((config.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) > Configuration.SCREENLAYOUT_SIZE_LARGE) {

			int originalScreenLayout = config.screenLayout;
			config.screenLayout = Configuration.SCREENLAYOUT_SIZE_LARGE;
			super.openOptionsMenu();
			config.screenLayout = originalScreenLayout;

		} else {
			super.openOptionsMenu();
		}
	}

}