package com.kail.location.inject.utils;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import com.kail.location.lib.lhooker.LHooker;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RootLocationControl {
    private static final String TAG = "RootLocationControl";
    private static final long LOOP_ALIVE_MS = 5000L;
    private static final long APPLY_ABANDON_MS = 20_000L;
    private static volatile boolean started;
    private static volatile Thread controlThread;
    private static volatile long lastLoopMs;
    private static volatile Thread applyWorker;
    private static volatile long applyWorkerTickedMs;
    private static volatile File pendingApplyFile;
    private static volatile Context context;
    private static volatile String controlPath = RootControlPaths.LEGACY_CONTROL_PATH;
    private static volatile String ackPath = RootControlPaths.LEGACY_ACK_PATH;
    private static long lastModified;
    private static long lastLength;
    private static long applyCount;
    private static long lastAllowModified;
    private static long lastAllowLength;
    private static long lastWifiModified;
    private static long lastWifiLength;
    private static long lastCellModified;
    private static long lastCellLength;
    private static volatile boolean lastStepEnabled;
    private static volatile float lastStepSpm = -1.0f;
    private static volatile int lastStepMode = -1;
    private static volatile int lastStepScheme = -1;
    private static volatile String lastStepStatus = "disabled";
    private static volatile String lastStepError;
    private static volatile Control lastControl;
    private static volatile String lastAckStatus = "started";
    private static volatile long lastAckRefreshMs;

    private RootLocationControl() {
    }

    public static synchronized void start(Context appContext) {
        if (appContext != null) {
            context = appContext;
            controlPath = RootControlPaths.controlPath(appContext);
            ackPath = RootControlPaths.ackPath(appContext);
        }
        Thread existing = controlThread;
        if (started && existing != null && existing.isAlive()
                && System.currentTimeMillis() - lastLoopMs < LOOP_ALIVE_MS) {
            return;
        }
        // 上次线程已退出或卡死（心跳过期）——强制拉起一个新线程，避免一次 apply 死锁
        // 让注入在本机重启前永久失效（PID 不变，App 侧靠标记会误判"已注入"）。
        started = true;
        lastModified = 0L;
        lastLength = 0L;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "KailRootLocationControl");
        thread.setDaemon(true);
        controlThread = thread;
        thread.start();
        writeAck("started", null, null);
        InjectLog.persist(TAG, "started context=", context, " control=", controlPath);
        // 文件通道白名单：启动时立即应用一次，随后由 loop() 按文件变化持续刷新。
        applyAllowPackagesFromFile();
    }

    private static void loop() {
        while (true) {
            try {
                lastLoopMs = System.currentTimeMillis();
                File file = new File(controlPath);
                long modified = file.exists() ? file.lastModified() : 0L;
                long length = file.exists() ? file.length() : 0L;
                if (modified != lastModified || length != lastLength) {
                    lastModified = modified;
                    lastLength = length;
                    scheduleApply(file);
                }
                refreshAllowMockPackagesIfNeeded();
                refreshWifiMockIfNeeded();
                refreshCellMockIfNeeded();
                refreshStepAckIfNeeded();
                Thread.sleep(250L);
            } catch (Throwable t) {
                InjectLog.e(TAG, "loop error", t);
                writeAck("error", null, t.toString());
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /**
     * 文件通道白名单轮询：<i>独立模拟</i>的目标应用白名单由 Kail 应用经 root 写入
     * {@link AllowMockPackagesConfigFile#PATH}，SELinux Enforcing 下 oem_location /
     * oem_wifi binder 不可用（addService 被拒、find 被拦），因此这里在 system_server
     * 内直接读文件并刷入 {@link MockLocationHookManager#setAllowMockPackages} /
     * {@link MockWifiConfigManager#setAllowMockPackages}，使白名单对位置 / GNSS /
     * 基站（经由 isAllowMockPackage 分发）与 WiFi 列表同时生效。
     */
    private static void refreshAllowMockPackagesIfNeeded() {
        try {
            File file = new File(AllowMockPackagesConfigFile.PATH);
            long modified = file.exists() ? file.lastModified() : 0L;
            long length = file.exists() ? file.length() : 0L;
            if (modified == lastAllowModified && length == lastAllowLength) {
                return;
            }
            lastAllowModified = modified;
            lastAllowLength = length;
            applyAllowPackagesFromFile();
        } catch (Throwable t) {
            InjectLog.e(TAG, "allow mock packages refresh error", t);
        }
    }

    /**
     * 读取白名单文件并刷新到 MockLocationHookManager / MockWifiConfigManager。
     * enabled=0 或 packages 为空时传 null（= 对所有应用生效，恢复默认行为）。
     */
    private static void applyAllowPackagesFromFile() {
        try {
            AllowMockPackagesConfigFile.Config cfg = AllowMockPackagesConfigFile.read();
            List<String> pkgs = cfg.enabled && !cfg.packages.isEmpty() ? cfg.packages : null;
            MockLocationHookManager.setAllowMockPackages(pkgs == null ? null : new ArrayList<String>(pkgs));
            MockWifiConfigManager.setAllowMockPackages(pkgs == null ? null : new ArrayList<String>(pkgs));
            InjectLog.persist(TAG, "allow mock packages applied: enabled=", cfg.enabled,
                    " pkgs=", pkgs == null ? "<all apps>" : pkgs.toString());
        } catch (Throwable t) {
            InjectLog.e(TAG, "allow mock packages apply error", t);
        }
    }

    /**
     * WiFi 模拟文件通道轮询：Kail 应用经 root 写 mock_wifi.txt，这里检测文件
     * 变化后刷入 MockWifiConfigManager。enabled=0 时只关闭模拟开关，不清网络
     * 列表（binder stopMockWifi 同语义）。
     */
    private static void refreshWifiMockIfNeeded() {
        try {
            File file = new File(WifiMockConfigFile.PATH);
            long modified = file.exists() ? file.lastModified() : 0L;
            long length = file.exists() ? file.length() : 0L;
            if (modified == lastWifiModified && length == lastWifiLength) {
                return;
            }
            lastWifiModified = modified;
            lastWifiLength = length;
            applyWifiMockFromFile();
        } catch (Throwable t) {
            InjectLog.e(TAG, "wifi mock refresh error", t);
        }
    }

    /**
     * 读取 WiFi 模拟文件并刷新到 MockWifiConfigManager。序列与
     * MockWifiManagerService.startMockWifi 等价：
     * 设置网络列表/主网络，License 可用时挂 WifiServiceHook 并置开关。
     */
    private static void applyWifiMockFromFile() {
        try {
            WifiMockConfigFile.Config cfg = WifiMockConfigFile.read();
            if (!cfg.enabled || cfg.networks.isEmpty()) {
                MockWifiConfigManager.setMockWifiEnabled(false);
                InjectLog.persist(TAG, "wifi mock disabled by file");
                return;
            }
            MockWifiConfigManager.setMockWifiNetworks(cfg.networks);
            MockWifiConfigManager.setPrimaryMockWifiNetwork(cfg.networks.get(0));
            if (LicenseStateManager.isLicenseUsable()
                    && com.kail.location.inject.fakelocation.InjectDex.getApplicationContext() != null) {
                if (!com.kail.location.inject.fakelocation.hook.system.WifiServiceHook.scanResultsHooked) {
                    com.kail.location.inject.fakelocation.hook.system.WifiServiceHook.hook(
                            com.kail.location.inject.fakelocation.InjectDex.getApplicationContext().getClassLoader());
                }
                if (!com.kail.location.inject.fakelocation.hook.system.WifiServiceHook.connectionInfoHooked) {
                    com.kail.location.inject.fakelocation.hook.system.WifiServiceHook.hookGetConnectionInfo(
                            com.kail.location.inject.fakelocation.InjectDex.getApplicationContext().getClassLoader());
                }
                MockWifiConfigManager.setMockWifiEnabled(true);
            }
            InjectLog.persist(TAG, "wifi mock applied: enabled=1 networks=", cfg.networks.size());
        } catch (Throwable t) {
            InjectLog.e(TAG, "wifi mock apply error", t);
        }
    }

    /**
     * 基站模拟文件通道轮询：Kail 应用经 root 写 mock_cell.txt，这里检测文件
     * 变化后刷入 MockLocationHookManager。enabled=0 时清空模拟小区
     * （binder setMockCells(null) 同语义）。
     */
    private static void refreshCellMockIfNeeded() {
        try {
            File file = new File(CellMockConfigFile.PATH);
            long modified = file.exists() ? file.lastModified() : 0L;
            long length = file.exists() ? file.length() : 0L;
            if (modified == lastCellModified && length == lastCellLength) {
                return;
            }
            lastCellModified = modified;
            lastCellLength = length;
            applyCellMockFromFile();
        } catch (Throwable t) {
            InjectLog.e(TAG, "cell mock refresh error", t);
        }
    }

    /**
     * 读取基站模拟文件并刷新到 MockLocationHookManager。序列与 App 侧
     * applyCellMockOnInjection（binder 路径）等价：
     * 置小区列表 + scoped 块列表（只放行基站 "e" 作用域）+ 打开 master mock +
     * seed 一个基准位置（供 CellInfoFactory/onCellLocationChanged 取坐标）。
     */
    private static void applyCellMockFromFile() {
        try {
            CellMockConfigFile.Config cfg = CellMockConfigFile.read();
            if (!cfg.enabled || cfg.towers.isEmpty()) {
                MockLocationHookManager.setMockCells(null);
                InjectLog.persist(TAG, "cell mock disabled by file");
                return;
            }
            MockLocationHookManager.setMockCells(cfg.towers);
            MockLocationHookManager.setSafeApps(new ArrayList<String>(java.util.Collections.singletonList("abhf|*")));
            MockLocationHookManager.setMockGpsStatus(false);
            MockLocationHookManager.startMockLocation();
            com.kail.location.inject.fakelocation.model.CellTowerInfo anchor = null;
            for (com.kail.location.inject.fakelocation.model.CellTowerInfo t : cfg.towers) {
                if (t.getLatitude() != 0.0 || t.getLongitude() != 0.0) {
                    anchor = t;
                    break;
                }
            }
            double baseLat = anchor != null ? anchor.getLatitude() : cfg.towers.get(0).getLatitude();
            double baseLng = anchor != null ? anchor.getLongitude() : cfg.towers.get(0).getLongitude();
            android.location.Location loc = new android.location.Location(android.location.LocationManager.GPS_PROVIDER);
            loc.setLatitude(baseLat);
            loc.setLongitude(baseLng);
            loc.setAltitude(0.0);
            loc.setAccuracy(25.0f);
            loc.setTime(System.currentTimeMillis());
            loc.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("from", "loc");
            loc.setExtras(extras);
            MockLocationHookManager.setMockLocation(loc);
            InjectLog.persist(TAG, "cell mock applied: towers=", cfg.towers.size(),
                    " anchor=", baseLat, ",", baseLng);
        } catch (Throwable t) {
            InjectLog.e(TAG, "cell mock apply error", t);
        }
    }

    /**
     * 在独立 worker 线程上执行 apply()。长时运行后向已注册（可能已冻结/僵尸）的
     * 位置监听器派发模拟位置会阻塞数秒甚至更长；如果直接在控制循环线程里 apply()，
     * 整个循环会被拖死，probe/ack 全部失联（表现为"过一段时间后注入失效"）。
     * worker 若超过 [APPLY_ABANDON_MS] 没有推进（apply 内层卡死），下一次
     * scheduleApply 直接放弃旧 worker 另起新的，控制循环保持可用。
     */
    private static synchronized void scheduleApply(File file) {
        pendingApplyFile = file;
        Thread worker = applyWorker;
        long now = System.currentTimeMillis();
        if (worker != null && worker.isAlive() && now - applyWorkerTickedMs < APPLY_ABANDON_MS) {
            return;
        }
        // 旧 worker 已死或卡死（超时无心跳）——放弃它，另起一个处理最新文件。
        applyWorkerTickedMs = now;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    File f;
                    synchronized (RootLocationControl.class) {
                        f = pendingApplyFile;
                        pendingApplyFile = null;
                        if (f == null) return;
                        applyWorkerTickedMs = System.currentTimeMillis();
                    }
                    try {
                        apply(f);
                    } catch (Throwable t2) {
                        InjectLog.e(TAG, "apply error", t2);
                        writeAck("error", null, t2.toString());
                    }
                }
            }
        }, "KailRootLocationApply");
        t.setDaemon(true);
        applyWorker = t;
        t.start();
    }

    private static void apply(File file) throws Exception {
        if (!file.exists() || file.length() <= 0) return;
        Control control = read(file);
        if (!control.enabled) {
            MockLocationHookManager.stopMockLocation();
            MockLocationHookManager.setMockGpsStatus(false);
            MockLocationHookManager.setMockCells(null);
            MockLocationHookManager.setSafeApps(null);
            applyStepControl(control);
            lastControl = control;
            writeAck("disabled", control, null);
            InjectLog.persist(TAG, "disabled by control file");
            return;
        }
        ensureLocationHooks();
        if (!MockLocationHookManager.initialized) {
            writeAck("init_failed", control, "MockLocationHookManager not initialized");
            return;
        }
        MockLocationHookManager.setSafeApps(null);
        MockLocationHookManager.setIntervalTimeout(control.intervalMs);
        MockLocationHookManager.setMockGpsStatus(true);
        MockLocationHookManager.startMockLocation();

        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(control.lat);
        location.setLongitude(control.lng);
        location.setAltitude(control.alt);
        location.setBearing((float) control.bearing);
        location.setSpeed((float) control.speed);
        location.setAccuracy(1.0f);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        Bundle extras = new Bundle();
        extras.putString("from", "rocker");
        location.setExtras(extras);
        MockLocationHookManager.setMockLocation(location);
        MockLocationHookManager.callLocationChanged(new Location(location));
        applyStepControl(control);
        lastControl = control;
        writeAck("applied", control, null);
        InjectLog.persist(TAG, "applied lat=", control.lat, " lng=", control.lng,
                " interval=", control.intervalMs, " step=", lastStepStatus,
                " synth=", NativeStepHook.getStepSynthEvents());
    }

    private static void applyStepControl(Control control) {
        boolean requested = control != null && control.enabled && control.stepEnabled;
        if (!requested) {
            if (lastStepEnabled || MockStepSensorManager.isStepSensorMocking()) {
                try {
                    MockStepSensorManager.stopStepSensorMock();
                    InjectLog.persist(TAG, "step control stopped");
                } catch (Throwable t) {
                    lastStepError = t.toString();
                    InjectLog.e(TAG, "step control stop failed", t);
                }
            }
            lastStepEnabled = false;
            lastStepSpm = -1.0f;
            lastStepMode = -1;
            lastStepScheme = -1;
            lastStepStatus = "disabled";
            if (!MockStepSensorManager.isStepSensorMocking()) {
                lastStepError = null;
            }
            return;
        }

        boolean changed = !lastStepEnabled
                || Math.abs(lastStepSpm - control.stepSpm) > 0.01f
                || lastStepMode != control.stepMode
                || lastStepScheme != control.stepScheme;
        if (!changed) {
            updateStepStatus();
            return;
        }

        try {
            MockStepSensorManager.setStepSpeed(control.stepSpm / 60.0f);
            MockStepSensorManager.startStepSensorMock(control.stepMode, control.stepScheme);
            lastStepEnabled = true;
            lastStepSpm = control.stepSpm;
            lastStepMode = control.stepMode;
            lastStepScheme = control.stepScheme;
            updateStepStatus();
            InjectLog.persist(TAG, "step control applied enabled=", control.stepEnabled,
                    " spm=", control.stepSpm, " mode=", control.stepMode,
                    " scheme=", control.stepScheme, " status=", lastStepStatus);
        } catch (Throwable t) {
            lastStepStatus = "error";
            lastStepError = t.toString();
            InjectLog.e(TAG, "step control apply failed", t);
        }
    }

    private static void updateStepStatus() {
        boolean mocking = MockStepSensorManager.isStepSensorMocking();
        boolean hookInstalled = NativeStepHook.isHookInstalled();
        long synth = NativeStepHook.getStepSynthEvents();
        if (!mocking) {
            lastStepStatus = "stopped";
            lastStepError = null;
        } else if (!hookInstalled) {
            lastStepStatus = "mocking_no_hook";
            lastStepError = "NativeStepHook not installed";
        } else if (synth > 0) {
            lastStepStatus = "running";
            lastStepError = null;
        } else if (MockStepSensorManager.getMockElapsedMillis() < 5000L) {
            lastStepStatus = "waiting_synth";
            lastStepError = null;
        } else {
            lastStepStatus = "no_synth_events";
            lastStepError = "No synthetic step events emitted";
        }
    }

    private static void refreshStepAckIfNeeded() {
        Control control = lastControl;
        if (control == null || !control.enabled || !control.stepEnabled) return;
        long now = System.currentTimeMillis();
        if (now - lastAckRefreshMs < 1000L) return;
        lastAckRefreshMs = now;
        updateStepStatus();
        writeAck(lastAckStatus, control, null);
    }

    private static void writeAck(String status, Control control, String error) {
        try {
            lastAckStatus = status;
            applyCount++;
            StringBuilder sb = new StringBuilder();
            sb.append("status=").append(status).append('\n');
            sb.append("pid=").append(Process.myPid()).append('\n');
            sb.append("time_ms=").append(System.currentTimeMillis()).append('\n');
            sb.append("count=").append(applyCount).append('\n');
            sb.append("control_path=").append(controlPath).append('\n');
            sb.append("lhooker_initialized=").append(LHooker.initialized).append('\n');
            sb.append("mock_initialized=").append(MockLocationHookManager.initialized).append('\n');
            if (control != null) {
                sb.append("enabled=").append(control.enabled ? 1 : 0).append('\n');
                sb.append("lat=").append(control.lat).append('\n');
                sb.append("lng=").append(control.lng).append('\n');
                sb.append("interval=").append(control.intervalMs).append('\n');
                sb.append("step_enabled=").append(control.stepEnabled ? 1 : 0).append('\n');
                sb.append("step_spm=").append(control.stepSpm).append('\n');
                sb.append("step_mocking=").append(MockStepSensorManager.isStepSensorMocking() ? 1 : 0).append('\n');
                sb.append("step_hook_installed=").append(NativeStepHook.isHookInstalled() ? 1 : 0).append('\n');
                sb.append("step_hook_state=").append(NativeStepHook.getHookState()).append('\n');
                sb.append("step_send_hook=").append(NativeStepHook.isSendObjectsHookInstalled() ? 1 : 0).append('\n');
                sb.append("step_convert_hook=").append(NativeStepHook.isConvertHookInstalled() ? 1 : 0).append('\n');
                sb.append("step_counter_handle=").append(MockStepSensorManager.getStepCounterHandle()).append('\n');
                sb.append("step_detector_handle=").append(MockStepSensorManager.getStepDetectorHandle()).append('\n');
                sb.append("step_synth_events=").append(NativeStepHook.getStepSynthEvents()).append('\n');
                sb.append("step_status=").append(lastStepStatus).append('\n');
                if (lastStepError != null) {
                    sb.append("step_error=").append(lastStepError.replace('\n', ' ')).append('\n');
                }
            }
            if (error != null) {
                sb.append("error=").append(error.replace('\n', ' ')).append('\n');
            }
            File file = new File(ackPath);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(file, false);
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            file.setReadable(true, false);
            file.setWritable(true, false);
        } catch (Throwable t) {
            InjectLog.e(TAG, "write ack failed", t);
        }
    }

    private static void ensureLocationHooks() {
        if (MockLocationHookManager.initialized) return;
        if (!LHooker.initialized) {
            String path = LHooker.isDeviceX86_64()
                    ? "/data/kail-loc/liblhookerx64.so"
                    : LHooker.isDeviceX86()
                    ? "/data/kail-loc/liblhookerx.so"
                    : LHooker.isDeviceArm64()
                    ? "/data/kail-loc/liblhooker64.so"
                    : "/data/kail-loc/liblhooker.so";
            InjectLog.persist(TAG, "LHooker not initialized; retry load path=", path);
            LHooker.loadHookLibrary(path);
            InjectLog.persist(TAG, "LHooker initialized after retry=", LHooker.initialized);
        }
        if (!LHooker.initialized) {
            InjectLog.e(TAG, "cannot init MockLocationHookManager: LHooker not initialized");
            return;
        }
        Context ctx = context;
        if (ctx == null) {
            InjectLog.e(TAG, "cannot init MockLocationHookManager: context is null");
            return;
        }
        InjectLog.persist(TAG, "initializing MockLocationHookManager");
        MockLocationHookManager.init(ctx);
        InjectLog.persist(TAG, "MockLocationHookManager initialized=", MockLocationHookManager.initialized);
    }

    private static Control read(File file) throws Exception {
        Control control = new Control();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf('=');
                if (idx <= 0) continue;
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if ("enabled".equals(key)) control.enabled = "1".equals(value) || "true".equalsIgnoreCase(value);
                else if ("lat".equals(key)) control.lat = Double.parseDouble(value);
                else if ("lng".equals(key)) control.lng = Double.parseDouble(value);
                else if ("alt".equals(key)) control.alt = Double.parseDouble(value);
                else if ("bearing".equals(key)) control.bearing = Double.parseDouble(value);
                else if ("speed".equals(key)) control.speed = Double.parseDouble(value);
                else if ("interval".equals(key)) control.intervalMs = Long.parseLong(value);
                else if ("step_enabled".equals(key)) control.stepEnabled = "1".equals(value) || "true".equalsIgnoreCase(value);
                else if ("step_spm".equals(key)) control.stepSpm = Float.parseFloat(value);
                else if ("step_mode".equals(key)) control.stepMode = Integer.parseInt(value);
                else if ("step_scheme".equals(key)) control.stepScheme = Integer.parseInt(value);
            }
        } finally {
            reader.close();
        }
        return control;
    }

    private static final class Control {
        boolean enabled;
        double lat;
        double lng;
        double alt;
        double bearing;
        double speed;
        long intervalMs = 1000L;
        boolean stepEnabled;
        float stepSpm = 120.0f;
        int stepMode;
        int stepScheme;
    }
}
