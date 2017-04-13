package org.witness.proofmode.util;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothClass;
import android.content.Context;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Debug;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class DeviceInfo {

    public static String getDeviceInfo(Context activity, Device device) {
        try {
            switch (device) {
                case DEVICE_LANGUAGE:
                    return Locale.getDefault().getDisplayLanguage();
                case DEVICE_TIME_ZONE:
                    return TimeZone.getDefault().getID();//(false, TimeZone.SHORT);
                case DEVICE_LOCAL_COUNTRY_CODE:
                    return activity.getResources().getConfiguration().locale.getCountry();
                case DEVICE_CURRENT_YEAR:
                    return "" + (Calendar.getInstance().get(Calendar.YEAR));
                case DEVICE_CURRENT_DATE_TIME:
                    Calendar calendarTime = Calendar.getInstance(TimeZone.getDefault(), Locale.getDefault());
                    long time = (calendarTime.getTimeInMillis() / 1000);
                    return String.valueOf(time);
                //                    return DateFormat.getDateTimeInstance().format(new Date());
                case DEVICE_CURRENT_DATE_TIME_ZERO_GMT:
                    Calendar calendarTime_zero = Calendar.getInstance(TimeZone.getTimeZone("GMT+0"), Locale.getDefault());
                    return String.valueOf((calendarTime_zero.getTimeInMillis() / 1000));
                //                    DateFormat df = DateFormat.getDateTimeInstance();
                //                    df.setTimeZone(TimeZone.getTimeZone("GMT+0"));
                //                    return df.format(new Date());
                case DEVICE_HARDWARE_MODEL:
                    return getDeviceName();
                case DEVICE_NUMBER_OF_PROCESSORS:
                    return Runtime.getRuntime().availableProcessors() + "";
                case DEVICE_LOCALE:
                    return Locale.getDefault().getISO3Country();
                case DEVICE_IP_ADDRESS_IPV4:
                    return getIPAddress(true);
                case DEVICE_IP_ADDRESS_IPV6:
                    return getIPAddress(false);
                case DEVICE_MAC_ADDRESS:
                    String mac = getMACAddress("wlan0");
                    if (TextUtils.isEmpty(mac)) {
                        mac = getMACAddress("eth0");
                    }
                    if (TextUtils.isEmpty(mac)) {
                        mac = "DU:MM:YA:DD:RE:SS";
                    }
                    return mac;

                case DEVICE_TOTAL_MEMORY:
                    if (Build.VERSION.SDK_INT >= 16)
                        return String.valueOf(getTotalMemory(activity));
                case DEVICE_FREE_MEMORY:
                    return String.valueOf(getFreeMemory(activity));
                case DEVICE_USED_MEMORY:
                    if (Build.VERSION.SDK_INT >= 16) {
                        long freeMem = getTotalMemory(activity) - getFreeMemory(activity);
                        return String.valueOf(freeMem);
                    }
                    return "";
                case DEVICE_TOTAL_CPU_USAGE:
                    int[] cpu = getCpuUsageStatistic();
                    if (cpu != null) {
                        int total = cpu[0] + cpu[1] + cpu[2] + cpu[3];
                        return String.valueOf(total);
                    }
                    return "";
                case DEVICE_TOTAL_CPU_USAGE_SYSTEM:
                    int[] cpu_sys = getCpuUsageStatistic();
                    if (cpu_sys != null) {
                        int total = cpu_sys[1];
                        return String.valueOf(total);
                    }
                    return "";
                case DEVICE_TOTAL_CPU_USAGE_USER:
                    int[] cpu_usage = getCpuUsageStatistic();
                    if (cpu_usage != null) {
                        int total = cpu_usage[0];
                        return String.valueOf(total);
                    }
                    return "";
                case DEVICE_MANUFACTURE:
                    return android.os.Build.MANUFACTURER;
                case DEVICE_SYSTEM_VERSION:
                    return String.valueOf(getDeviceName());
                case DEVICE_VERSION:
                    return String.valueOf(android.os.Build.VERSION.SDK_INT);
                case DEVICE_IN_INCH:
                    return getDeviceInch(activity);
                case DEVICE_TOTAL_CPU_IDLE:
                    int[] cpu_idle = getCpuUsageStatistic();
                    if (cpu_idle != null) {
                        int total = cpu_idle[2];
                        return String.valueOf(total);
                    }
                    return "";
                case DEVICE_NETWORK_TYPE:
                    return getNetworkType(activity);
                case DEVICE_NETWORK:
                    return checkNetworkStatus(activity);
                case DEVICE_TYPE:
                    if (isTablet(activity)) {
                        if (getDeviceMoreThan5Inch(activity)) {
                            return "Tablet";
                        } else
                            return "Mobile";
                    } else {
                        return "Mobile";
                    }
                default:
                    break;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return "";
    }

    public static String getDeviceId(Context context) {
        String device_uuid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (device_uuid == null) {
            device_uuid = "12356789"; // for emulator testing
        } else {
            try {
                byte[] _data = device_uuid.getBytes();
                MessageDigest _digest = java.security.MessageDigest.getInstance("MD5");
                _digest.update(_data);
                _data = _digest.digest();
                BigInteger _bi = new BigInteger(_data).abs();
                device_uuid = _bi.toString(36);
            } catch (Exception e) {
                if (e != null) {
                    e.printStackTrace();
                }
            }
        }
        return device_uuid;
    }

    @SuppressLint("NewApi")
    private static long getTotalMemory(Context activity) {
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            activityManager.getMemoryInfo(mi);
            long availableMegs = mi.totalMem / 1048576L; // in megabyte (mb)

            return availableMegs;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static long getFreeMemory(Context activity) {
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            activityManager.getMemoryInfo(mi);
            long availableMegs = mi.availMem / 1048576L; // in megabyte (mb)

            return availableMegs;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.startsWith(manufacturer)) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        } else {
            return Character.toUpperCase(first) + s.substring(1);
        }
    }

    /**
     * Convert byte array to hex string
     *
     * @param bytes
     * @return
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sbuf = new StringBuilder();
        for (int idx = 0; idx < bytes.length; idx++) {
            int intVal = bytes[idx] & 0xff;
            if (intVal < 0x10)
                sbuf.append("0");
            sbuf.append(Integer.toHexString(intVal).toUpperCase());
        }
        return sbuf.toString();
    }

    /**
     * Returns MAC address of the given interface name.
     *
     * @param interfaceName eth0, wlan0 or NULL=use first interface
     * @return mac address or empty string
     */
    @SuppressLint("NewApi")
    private static String getMACAddress(String interfaceName) {
        try {

            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (interfaceName != null) {
                    if (!intf.getName().equalsIgnoreCase(interfaceName))
                        continue;
                }
                byte[] mac = intf.getHardwareAddress();
                if (mac == null)
                    return "";
                StringBuilder buf = new StringBuilder();
                for (int idx = 0; idx < mac.length; idx++)
                    buf.append(String.format("%02X:", mac[idx]));
                if (buf.length() > 0)
                    buf.deleteCharAt(buf.length() - 1);
                return buf.toString();
            }
        } catch (Exception ex) {
            return "";
        } // for now eat exceptions
        return "";
            /*
             * try { // this is so Linux hack return
             * loadFileAsString("/sys/class/net/" +interfaceName +
             * "/address").toUpperCase().trim(); } catch (IOException ex) { return
             * null; }
             */
    }

    /**
     * Get IP address from first non-localhost interface
     *
     * @return address or empty string
     */
    private static String getIPAddress(boolean useIPv4) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress().toUpperCase();
                        boolean isIPv4 = addr instanceof Inet4Address;
                        if (useIPv4) {
                            if (isIPv4)
                                return sAddr;
                        } else {
                            if (!isIPv4) {
                                int delim = sAddr.indexOf('%'); // drop ip6 port
                                // suffix
                                return delim < 0 ? sAddr : sAddr.substring(0, delim);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
        } // for now eat exceptions
        return "";
    }

    /*
     *
     * @return integer Array with 4 elements: user, system, idle and other cpu
     * usage in percentage.
     */
    private static int[] getCpuUsageStatistic() {
        try {
            String tempString = executeTop();

            tempString = tempString.replaceAll(",", "");
            tempString = tempString.replaceAll("User", "");
            tempString = tempString.replaceAll("System", "");
            tempString = tempString.replaceAll("IOW", "");
            tempString = tempString.replaceAll("IRQ", "");
            tempString = tempString.replaceAll("%", "");
            for (int i = 0; i < 10; i++) {
                tempString = tempString.replaceAll("  ", " ");
            }
            tempString = tempString.trim();
            String[] myString = tempString.split(" ");
            int[] cpuUsageAsInt = new int[myString.length];
            for (int i = 0; i < myString.length; i++) {
                myString[i] = myString[i].trim();
                cpuUsageAsInt[i] = Integer.parseInt(myString[i]);
            }
            return cpuUsageAsInt;

        } catch (Exception e) {
            e.printStackTrace();
            Log.e("executeTop", "error in getting cpu statics");
            return null;
        }
    }

    private static String executeTop() {
        java.lang.Process p = null;
        BufferedReader in = null;
        String returnString = null;
        try {
            p = Runtime.getRuntime().exec("top -n 1");
            in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (returnString == null || returnString.contentEquals("")) {
                returnString = in.readLine();
            }
        } catch (IOException e) {
            Log.e("executeTop", "error in getting first line of top");
            e.printStackTrace();
        } finally {
            try {
                in.close();
                p.destroy();
            } catch (IOException e) {
                Log.e("executeTop", "error in closing and destroying top process");
                e.printStackTrace();
            }
        }
        return returnString;
    }

    public static String getNetworkType(final Context activity) {
        String networkStatus = "";

        final ConnectivityManager connMgr = (ConnectivityManager)
                activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        // check for wifi
        final android.net.NetworkInfo wifi =
                connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        // check for mobile data
        final android.net.NetworkInfo mobile =
                connMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

        if (wifi.isConnected()) {
            networkStatus = "Wifi";
        } else if (mobile.isConnected()) {
            networkStatus = getDataType(activity);
        } else {
            networkStatus = "noNetwork";
        }
        return networkStatus;
    }

    public static String checkNetworkStatus(final Context activity) {
        String networkStatus = "";
        try {
            // Get connect mangaer
            final ConnectivityManager connMgr = (ConnectivityManager)
                    activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            // // check for wifi
            final android.net.NetworkInfo wifi =
                    connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            // // check for mobile data
            final android.net.NetworkInfo mobile =
                    connMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (wifi.isConnected()) {
                networkStatus = "Connected";
            } else if (mobile.isConnected()) {
                networkStatus = "Connected";
            } else {
                networkStatus = "noNetwork";
                networkStatus = "0";
            }


        } catch (Exception e) {
            e.printStackTrace();
            networkStatus = "0";
        }
        return networkStatus;

    }

    public static boolean isTablet(Context context) {
        return (context.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    public static boolean getDeviceMoreThan5Inch(Context activity) {
        try {
            DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();
            // int width = displayMetrics.widthPixels;
            // int height = displayMetrics.heightPixels;

            float yInches = displayMetrics.heightPixels / displayMetrics.ydpi;
            float xInches = displayMetrics.widthPixels / displayMetrics.xdpi;
            double diagonalInches = Math.sqrt(xInches * xInches + yInches * yInches);
            if (diagonalInches >= 7) {
                // 5inch device or bigger
                return true;
            } else {
                // smaller device
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static String getDeviceInch(Context activity) {
        try {
            DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();

            float yInches = displayMetrics.heightPixels / displayMetrics.ydpi;
            float xInches = displayMetrics.widthPixels / displayMetrics.xdpi;
            double diagonalInches = Math.sqrt(xInches * xInches + yInches * yInches);
            return String.valueOf(diagonalInches);
        } catch (Exception e) {
            return "-1";
        }
    }

    public static String getDataType(Context activity) {
        String type = "Mobile Data";
        TelephonyManager tm = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
        switch (tm.getNetworkType()) {
            case TelephonyManager.NETWORK_TYPE_HSDPA:
                type = "Mobile Data 3G";
                Log.d("Type", "3g");
                // for 3g HSDPA networktype will be return as
                // per testing(real) in device with 3g enable
                // data
                // and speed will also matters to decide 3g network type
                break;
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                type = "Mobile Data 4G";
                Log.d("Type", "4g");
                // No specification for the 4g but from wiki
                // i found(HSPAP used in 4g)
                break;
            case TelephonyManager.NETWORK_TYPE_GPRS:
                type = "Mobile Data GPRS";
                Log.d("Type", "GPRS");
                break;
            case TelephonyManager.NETWORK_TYPE_EDGE:
                type = "Mobile Data EDGE 2G";
                Log.d("Type", "EDGE 2g");
                break;

        }

        return type;
    }


    public enum Device {
        DEVICE_TYPE, DEVICE_SYSTEM_NAME, DEVICE_VERSION, DEVICE_SYSTEM_VERSION, DEVICE_TOKEN,
        /**
         *
         */
        DEVICE_NAME, DEVICE_UUID, DEVICE_MANUFACTURE, IPHONE_TYPE,
        /**
         *
         */
        CONTACT_ID, DEVICE_LANGUAGE, DEVICE_TIME_ZONE, DEVICE_LOCAL_COUNTRY_CODE,
        /**
         *
         */
        DEVICE_CURRENT_YEAR, DEVICE_CURRENT_DATE_TIME, DEVICE_CURRENT_DATE_TIME_ZERO_GMT,
        /**
         *
         */
        DEVICE_HARDWARE_MODEL, DEVICE_NUMBER_OF_PROCESSORS, DEVICE_LOCALE, DEVICE_NETWORK, DEVICE_NETWORK_TYPE,
        /**
         *
         */
        DEVICE_IP_ADDRESS_IPV4, DEVICE_IP_ADDRESS_IPV6, DEVICE_MAC_ADDRESS, DEVICE_TOTAL_CPU_USAGE,
        /**
         *
         */
        DEVICE_TOTAL_MEMORY, DEVICE_FREE_MEMORY, DEVICE_USED_MEMORY,
        /**
         *
         */
        DEVICE_TOTAL_CPU_USAGE_USER, DEVICE_TOTAL_CPU_USAGE_SYSTEM,
        /**
         *
         */
        DEVICE_TOTAL_CPU_IDLE, DEVICE_IN_INCH;
    }

    public static String getWifiMacAddr() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : all) {
                if (!nif.getName().equalsIgnoreCase("wlan0")) continue;

                byte[] macBytes = nif.getHardwareAddress();
                if (macBytes == null) {
                    return "";
                }

                StringBuilder res1 = new StringBuilder();
                for (byte b : macBytes) {
                    res1.append(Integer.toHexString(b & 0xFF) + ":");
                }

                if (res1.length() > 0) {
                    res1.deleteCharAt(res1.length() - 1);
                }
                return res1.toString();
            }
        } catch (Exception ex) {
        }
        return "02:00:00:00:00:00";
    }
}
