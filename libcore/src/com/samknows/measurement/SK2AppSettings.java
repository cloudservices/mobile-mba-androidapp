package com.samknows.measurement;

import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import org.apache.commons.io.IOUtils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.samknows.libcore.R;
import com.samknows.libcore.SKLogger;
import com.samknows.libcore.SKConstants;
import com.samknows.measurement.schedule.ScheduleConfig;
import com.samknows.measurement.schedule.ScheduleConfig.LocationType;
import com.samknows.measurement.schedule.ScheduleConfig.TestAlarmType;
import com.samknows.measurement.statemachine.State;
import com.samknows.measurement.util.TimeUtils;
import com.samknows.measurement.util.OtherUtils;

public class SK2AppSettings extends SKAppSettings {
	private static final String TAG = SK2AppSettings.class.getName();
	
	//json fields to be included to each submission
	public static final String JSON_UNIT_ID 				= "unit_id";
	public static final String JSON_APP_VERSION_CODE 		= "app_version_code";
	public static final String JSON_APP_VERSION_NAME 		= "app_version_name";
	public static final String JSON_SCHEDULE_CONFIG_VERSION = "schedule_config_version";
	public static final String JSON_TIMEZONE 				= "timezone";
	public static final String JSON_TIMESTAMP 				= "timestamp";
	public static final String JSON_DATETIME 				= "datetime";
	public static final String JSON_ENTERPRISE_ID 			= "enterprise_id";
	public static final String JSON_SIMOPERATORCODE 		= "sim_operator_code";
	public static final String JSON_USER_SELF_ID 			= "user_self_id";
	
	//Used to know if the app needs to collect identifiers 
	public boolean anonymous;
	
	//protocol scheme used for comunicating with the dcs
	public String protocol_scheme;
	
	//submit path used to send the results to the dcs
	public String submit_path;

	
	//download config file path
	public String download_config_path;
	
	//Enterprise id read from the properties file
	public String enterprise_id;
	
	//User self identifier preference enabled
	public boolean user_self_id = false;
	
	//Show data cap form after installation or upgrade
	public boolean data_cap_welcome;
	
	//collect net usage data
	public boolean collect_traffic_data;
	
	//Initialise the AppSettings reading from the properties file located in res/raw
	private SK2AppSettings(Context c) {
	    super(c);
	
		int propertiesId = c.getResources().getIdentifier("properties", "raw", c.getPackageName());
		
		InputStream is = c.getResources().openRawResource(propertiesId);
		Properties p = new Properties();
		try {
			p.load(is);
						
			testStartWindowWakeup 	= Long.valueOf(p.getProperty(SKConstants.PROP_TEST_START_WINDOW_RTC_WAKEUP));
			anonymous 				= Boolean.valueOf(p.getProperty(SKConstants.PROP_ANONYMOUS));
			protocol_scheme 		= p.getProperty(SKConstants.PROP_PROTOCOL_SCHEME);
			submit_path 			= p.getProperty(SKConstants.PROP_SUBMIT_PATH);
			download_config_path 	= p.getProperty(SKConstants.PROP_DOWNLOAD_CONFIG_PATH);

			enterprise_id 			= p.getProperty(SKConstants.PROP_ENTERPRISE_ID);
			user_self_id 			= Boolean.parseBoolean(p.getProperty(SKConstants.PROP_USER_SELF_IDENTIFIER));
			data_cap_welcome		= Boolean.parseBoolean(p.getProperty(SKConstants.PROP_DATA_CAP_WELCOME));
			collect_traffic_data	= Boolean.parseBoolean(p.getProperty(SKConstants.PROP_COLLECT_TRAFFIC_DATA));
					
		} catch (IOException e) {
			SKLogger.e(TAG, "failed to load properies!");
		} catch(NullPointerException npe){
			// This should be seen only when running a mock test.
			Log.e(this.getClass().getName(), "NullPointerException - make sure this happens only when running a mock test!");
			SKLogger.e(TAG, npe.getMessage());
			app_version_code = 0;
		} finally {
			IOUtils.closeQuietly(is);
		}
	}
	
	//to be called when the app starts, via:
	public static void create(Context c) {
		SKAppSettings.instance = new SK2AppSettings(c);
	}
	
	public static SK2AppSettings getSK2AppSettingsInstance() {
		return (SK2AppSettings)instance;
	}

	
	
	public boolean updateConfig(ScheduleConfig newConfig){
		boolean ret = false;
		ScheduleConfig savedConfig = CachingStorage.getInstance().loadScheduleConfig();
		if(savedConfig == null ){
			SKLogger.d(this, "Saved Config is null");
			ret = true;
		}else if(savedConfig.toUpdate(newConfig)){
			SKLogger.d(this, "Config versions don't match");
			ret = true;
		}
		if(forceDownload()){
			SKLogger.d(this, "Force update config");
			ret = true;
		}
		return ret;
	}
		
	public void setConfig(ScheduleConfig config){
		setWakeUpEnabledIfNull(config.testAlamType == TestAlarmType.WAKEUP);
		setLocationTypeIfNull(config.locationType);
		saveLong("number_of_tests_schedueld",config.getNumberOfScheduledBatch());
		saveBoolean("background_test",config.backgroundTest);
		if (config.dataCapDefault >= 0) {
			saveDataCapFromConfig(config.dataCapDefault);
		}
	}

	public void saveState(State state) {
		saveString(SKConstants.PREF_KEY_STATE, String.valueOf(state));
	}
	
	public State getState() {
		State ret = State.NONE;

		String state = getString(SKConstants.PREF_KEY_STATE);
		if (state != null) {
			for(State s:State.values()){
				if(state.equalsIgnoreCase(String.valueOf(s))){
					ret = s;
					break;
				}
			}
		}
		return ret;
	}


	public boolean isServiceEnabled(){
		boolean ret = false;
		SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
		if(p.contains(SKConstants.PREF_SERVICE_ENABLED)){
			ret =  p.getBoolean(SKConstants.PREF_SERVICE_ENABLED, false);
		}else{
			ret = getBoolean("background_test",true);
		}
		return ret;
		
	}

	// Methods for managing the testStartWindow
	public long getTestStartWindow(){
		return isWakeUpEnabled() ? testStartWindowWakeup : testStartWindow; 
	}

	
	//if the state machine fails and the user want to run some test we ask for the activation
	public void stateMachineFailure(){
		saveBoolean(SKConstants.STATE_MACHINE_STATUS, false);
	}
	
	public void stateMachineSuccess(){
		saveBoolean(SKConstants.STATE_MACHINE_STATUS, true);
		
	}
	
	public boolean stateMachineStatus(){
		return getBoolean(SKConstants.STATE_MACHINE_STATUS, true);
	}
	
	
	public void appendUsedBytes(long bytes) {
		if(OtherUtils.isWifi(ctx)){
			return;
		}
		resetDataUsageIfTime();
		long newBytes = getUsedBytes() + bytes;
		saveLong(SKConstants.PREF_KEY_USED_BYTES,newBytes);
		long newTime = System.currentTimeMillis();
		saveLong(SKConstants.PREF_KEY_USED_BYTES_LAST_TIME, newTime);
		SKLogger.d(TAG, "saved used bytes " + newBytes + " at time "+ newTime);
	}
	
	public long getUsedBytes() {
		return getLong(SKConstants.PREF_KEY_USED_BYTES, 0);
	}
	
	public void saveDataCapFromConfig(long bytes){
		saveLong(SKConstants.PREF_DATA_CAP, bytes);
	}
	/**
	 * data cap in bytes
	 * if preference has been defined use it
	 * otherwise use the datacap from config file
	 * if none of them is defined use 1024L*1024L
	 * 	  
	 */
	public long getDataCapBytes() {
		long ret = Long.MAX_VALUE;
		long configDataCap = getLong(SKConstants.PREF_DATA_CAP,-1);
		
		long preferenceDataCap = Long.valueOf(PreferenceManager.getDefaultSharedPreferences(ctx).getString(SKConstants.PREF_DATA_CAP, "-1")); //in megs
		if(preferenceDataCap>0){
			ret = preferenceDataCap * 1024L * 1024L;
		}else if(configDataCap > 0){
			ret = configDataCap * 1024L * 1024L;
		}
		return ret;
	}
	

	public void resetDataUsage(){
		saveLong(SKConstants.PREF_KEY_USED_BYTES,0);
	}
	
	private void resetDataUsageIfTime(){
		Calendar c = GregorianCalendar.getInstance();
		long startToday = TimeUtils.getStartDayTime();
		long startDayLastTime =TimeUtils.getStartDayTime(getLong(SKConstants.PREF_KEY_USED_BYTES_LAST_TIME, System.currentTimeMillis()));
		int currDayReset = PreferenceManager.getDefaultSharedPreferences(ctx).getInt(SKConstants.PREF_KEY_DATA_CAP_DAY_IN_MONTH_RESET, 1);
		long timeReset = TimeUtils.getPreviousDayInMonth(currDayReset);
		if( startDayLastTime < timeReset && startToday >= timeReset){
			SKLogger.d(this, "Data usage has been reset to 0 for the month useage. Reset time: "+timeReset+" last time: "+ startDayLastTime+" now: "+startToday);
			resetDataUsage();
		}
	}
	
	public boolean isDataCapReached(long bytesToBeUsed){
		if(OtherUtils.isWifi(ctx)){	
			return false;
		}
		resetDataUsageIfTime();
		long usedBytes = getUsedBytes();
		long dataCapBytes = getDataCapBytes();
		SKLogger.d(this, "Currently used bytes "+usedBytes+", DataCap is "+dataCapBytes+", bytes to be used "+ bytesToBeUsed +"." );
		return usedBytes + bytesToBeUsed >= dataCapBytes;
	}
	
	public boolean isDataCapReached() {
		return isDataCapReached(0);
	}
	
	public LocationType getLocationServiceType() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
		if (prefs.contains(SKConstants.PREF_LOCATION_TYPE)) {
			String pref = prefs.getString(SKConstants.PREF_LOCATION_TYPE, null);
			
			if(pref == null){
				return null;
			}
			
			if(pref.equals(ctx.getString(R.string.GPS))) {
				return LocationType.gps;
			}
			
			return LocationType.network;
		}
		return LocationType.gps;
	}
	
	public void setLocationTypeIfNull(LocationType type) {
		if (!PreferenceManager.getDefaultSharedPreferences(ctx).contains(SKConstants.PREF_LOCATION_TYPE)) {
			String value = type == LocationType.gps ? ctx.getString(R.string.GPS) : ctx.getString(R.string.MobileNetwork);
			PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(SKConstants.PREF_LOCATION_TYPE, value).commit();
		}
	}
	
	//Returns a Map containing all the json entries to be added when submitting the results
	public Map<String,Object> getJSONExtra(){
		Map<String, Object> ret= new HashMap<String,Object>();
		if(!anonymous){
			ret.put(JSON_UNIT_ID, getUnitId());
		}
		ret.put(JSON_APP_VERSION_NAME, app_version_name);
		ret.put(JSON_APP_VERSION_CODE, app_version_code);
		ScheduleConfig config = CachingStorage.getInstance().loadScheduleConfig();
		if( config !=null){
			ret.put(JSON_SCHEDULE_CONFIG_VERSION, config.version );
		}else{
			ret.put(JSON_SCHEDULE_CONFIG_VERSION, "no_schedule_config" );
		}
		long time = System.currentTimeMillis();
		ret.put(JSON_TIMESTAMP, (time/1000));
		ret.put(JSON_DATETIME, new java.util.Date(time).toString());
		ret.put(JSON_TIMEZONE, TimeUtils.millisToHours(TimeZone.getDefault().getRawOffset()));
		if(enterprise_id != null){
			ret.put(JSON_ENTERPRISE_ID, enterprise_id);
		}
		
		if (SK2AppSettings.getSK2AppSettingsInstance().user_self_id) {
			Log.e(this.getClass().toString(), "user_self_id is set to true!");
			String user_self_id = PreferenceManager.getDefaultSharedPreferences(ctx).getString(SKConstants.PREF_USER_SELF_ID, null);
			if(user_self_id != null ){
				Log.e(this.getClass().toString(), "user_self_id written to json (" + user_self_id + ")");
				ret.put(JSON_USER_SELF_ID, user_self_id);
			}
		}
		
		TelephonyManager manager = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
		String simOperatorCode = "";
		if(manager != null){
			simOperatorCode = manager.getSimOperator();
		}
		ret.put(JSON_SIMOPERATORCODE, simOperatorCode);
		
		return ret;
	}

}