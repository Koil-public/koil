package com.spirit.koil.api.util.system;

import java.awt.*;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class DeviceInfoManager {
    private static final String INTERNET_PROBE_URL = "https://raw.githubusercontent.com/";
    private static final int INTERNET_CONNECT_TIMEOUT_MS = 1500;
    private static final int INTERNET_READ_TIMEOUT_MS = 1500;
    private static final long INTERNET_STATUS_CACHE_MS = 10000L;

    private static final Properties systemProperties = System.getProperties();
    private static volatile long lastInternetCheckAt = 0L;
    private static volatile boolean lastInternetCheckResult = false;

    public static String getOperatingSystem() {
        return systemProperties.getProperty("os.name");
    }

    public static String getOperatingSystemArch() {
        return systemProperties.getProperty("os.arch");
    }
    public static String getJavaVersion() {
        return systemProperties.getProperty("java.version");
    }

    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "Unknown Host";
        }
    }

    public static String getUserName() {
        return systemProperties.getProperty("user.name");
    }

    public static long getTotalMemory() {
        return Runtime.getRuntime().totalMemory() / (1024 * 1024);
    }

    public static long getMaxMemory() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    public static int getAvailableProcessors() {
        return Runtime.getRuntime().availableProcessors();
    }

    public static String getScreenResolution() {
        try {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            return screenSize.width + "x" + screenSize.height;
        } catch (HeadlessException e) {
            return "Unknown (Headless environment)";
        }
    }

    public static Map<String, String> getEnvironmentVariables() {
        return System.getenv();
    }

    public static List<String> getNetworkInterfaces() {
        try {
            List<String> interfaces = new ArrayList<>();
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(networkInterface.getDisplayName()).append(": ");
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        sb.append(addresses.nextElement().toString()).append(" ");
                    }
                    interfaces.add(sb.toString().trim());
                }
            }
            return interfaces;
        } catch (SocketException e) {
            return List.of("Unable to fetch network interfaces");
        }
    }

    public static long getJvmUptime() {
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    public static double getSystemLoadAverage() {
        return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }

    public static long getFreeDiskSpace() {
        return new java.io.File("/").getFreeSpace() / (1024 * 1024);
    }

    public static long getTotalDiskSpace() {
        return new java.io.File("/").getTotalSpace() / (1024 * 1024);
    }

    public static List<String> getJvmArguments() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }

    public static String getTimeZone() {
        return TimeZone.getDefault().getID();
    }

    public static String getSystemLocale() {
        return Locale.getDefault().toString();
    }

    public static String getEnvironmentVariablesString() {
        return getEnvironmentVariables().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    public static String getNetworkStatus(String interfaceName) {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
            if (networkInterface != null && networkInterface.isUp()) {
                return interfaceName + " is connected";
            }
            return interfaceName + " is not connected";
        } catch (SocketException e) {
            return "Unable to determine status of " + interfaceName;
        }
    }

    public static boolean hasInternetAccess() {
        long now = System.currentTimeMillis();
        if (now - lastInternetCheckAt <= INTERNET_STATUS_CACHE_MS) {
            return lastInternetCheckResult;
        }

        boolean reachable = hasActiveNonLoopbackInterface() && probeInternetReachability();
        lastInternetCheckResult = reachable;
        lastInternetCheckAt = now;
        return reachable;
    }

    private static boolean hasActiveNonLoopbackInterface() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    return true;
                }
            }
            return false;
        } catch (SocketException e) {
            return false;
        }
    }

    private static boolean probeInternetReachability() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(INTERNET_PROBE_URL).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(INTERNET_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(INTERNET_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            connection.disconnect();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception ignored) {
            return false;
        }
    }
}
