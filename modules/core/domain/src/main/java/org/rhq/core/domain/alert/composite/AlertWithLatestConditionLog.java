/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License, version 2, as
 * published by the Free Software Foundation, and/or the GNU Lesser
 * General Public License, version 2.1, also as published by the Free
 * Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License and the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License
 * and the GNU Lesser General Public License along with this program;
 * if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.rhq.core.domain.alert.composite;

import java.io.Serializable;

import org.rhq.core.domain.alert.Alert;

public class AlertWithLatestConditionLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Alert alert;
    private final String conditionText;
    private final String conditionValue;
    private final String recoveryInfo;

    public AlertWithLatestConditionLog(Alert alert, String conditionText, String conditionValue, String recoveryInfo) {
        super();
        this.alert = alert;
        this.conditionText = conditionText;
        this.conditionValue = conditionValue;
        this.recoveryInfo = recoveryInfo;
    }

    public Alert getAlert() {
        return alert;
    }

    public String getConditionText() {
        return conditionText;
    }

    public String getConditionValue() {
        return conditionValue;
    }

    public String getRecoveryInfo() {
        return recoveryInfo;
    }
}