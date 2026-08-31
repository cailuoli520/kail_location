package com.kail.location.inject.fakelocation.service;

import com.kail.location.lib.lhooker.LHooker;
import com.kail.location.inject.fakelocation.aidl.INativeCatchManager;

/**
 * 原生层捕获/注入状态查询服务（以 oem_native 名注册）。
 *
 * 仅向宿主 Kail 应用暴露只读状态：当前功能实现为"桩"——
 * 状态均基于 {@link LHooker.initialized} 推导（是否已在原生层挂载 hooker 库），
 * 没有独立的启用开关。返回码约定：0 = 就绪，-1 = 未初始化。
 */
public class NativeCatchManagerService extends INativeCatchManager.Stub {

    /**
     * 查询原生层注入的初始化状态。
     *
     * @return 0 = LHooker 已初始化；-1 = 未初始化
     */
    @Override
    public int getNativeCatchInitStatus() {
        return !LHooker.initialized ? -1 : 0;
    }

    /**
     * 查询原生层捕获功能是否启用（当前为占位实现，恒为 false）。
     *
     * @return 恒为 false
     */
    @Override
    public boolean isNativeCatchEnabled() {
        boolean initialized = LHooker.initialized;
        return false;
    }

    /**
     * 查询原生层 hook 挂载状态。
     *
     * @return 0 = LHooker 已初始化；-1 = 未初始化
     */
    @Override
    public int getNativeCatchHookStatus() {
        return !LHooker.initialized ? -1 : 0;
    }
}
