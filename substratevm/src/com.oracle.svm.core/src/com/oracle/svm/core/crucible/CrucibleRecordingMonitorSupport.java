/*
 * Copyright (c) 2026, CrucibleVM contributors. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.crucible;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.monitor.JavaMonitor;
import com.oracle.svm.core.monitor.MonitorInflationCause;
import com.oracle.svm.core.monitor.MultiThreadedMonitorSupport;

/**
 * The monitor support of an image that records: it notes the type of every object a monitor is
 * looked up for, which is every object that is locked, waited on or notified. The image built from
 * the recording gives a monitor slot to those types, and can leave it out of the types the run
 * used without ever locking them.
 * <p>
 * Every lock of an object passes one of the two methods below at least once, when its monitor is
 * made, and what is noted is a type, so once is enough.
 */
public final class CrucibleRecordingMonitorSupport extends MultiThreadedMonitorSupport {

    @Platforms(Platform.HOSTED_ONLY.class)
    public CrucibleRecordingMonitorSupport() {
    }

    @Override
    protected JavaMonitor getOrCreateMonitorFromObject(Object obj, boolean createIfNotExisting, int monitorOffset, MonitorInflationCause cause) {
        if (createIfNotExisting) {
            CrucibleProfileRuntime.noteLocked(obj);
        }
        return super.getOrCreateMonitorFromObject(obj, createIfNotExisting, monitorOffset, cause);
    }

    @Override
    protected JavaMonitor getOrCreateMonitorFromMap(Object obj, boolean createIfNotExisting, MonitorInflationCause cause) {
        if (createIfNotExisting) {
            CrucibleProfileRuntime.noteLocked(obj);
        }
        return super.getOrCreateMonitorFromMap(obj, createIfNotExisting, cause);
    }
}
