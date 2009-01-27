/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.gui.common.metric;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;
import javax.faces.context.ResponseWriter;
import javax.faces.render.Renderer;

import com.sun.faces.util.MessageUtils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.gui.util.FacesContextUtility;
import org.rhq.enterprise.gui.common.metric.MetricComponent.TimeUnit;
import org.rhq.enterprise.gui.legacy.WebUser;
import org.rhq.enterprise.gui.util.EnterpriseFacesContextUtility;
import org.rhq.enterprise.server.measurement.MeasurementPreferences;
import org.rhq.enterprise.server.measurement.MeasurementPreferences.MetricRangePreferences;

/**
 * @author Fady Matar
 */
public class MetricRenderer extends Renderer {
    private final Log log = LogFactory.getLog(this.getClass());

    private static final ArrayList<Integer> timeIntervalValues = new ArrayList<Integer>(Arrays.asList(4, 8, 12, 24, 30,
        36, 48, 60, 90, 120));

    @Override
    public void decode(FacesContext context, UIComponent component) {
        super.decode(context, component);
        if (context == null) {
            throw new NullPointerException(MessageUtils.getExceptionMessageString(
                MessageUtils.NULL_PARAMETERS_ERROR_MESSAGE_ID, "context"));
        }

        if (component == null) {
            throw new NullPointerException(MessageUtils.getExceptionMessageString(
                MessageUtils.NULL_PARAMETERS_ERROR_MESSAGE_ID, "component"));
        }

        if (!component.isRendered()) {
            return;
        }

        MetricComponent metric;
        if (component instanceof MetricComponent) {
            metric = (MetricComponent) component;
        } else {
            return;
        }

        String metricUnit = getMetricUnit();
        Integer metricValue = getMetricValue();

        if (metricUnit != null) {
            // in simple settings mode, range might have been changed
            metric.setUnit(metricUnit);
            metric.setValue(metricValue);
            metric.persist();
        } else {
            // advanced mode can not be edited in this fashion
        }
    }

    public int getMetricValue() {
        return FacesContextUtility.getOptionalRequestParameter(MetricComponent.VALUE, Integer.class, -1);
    }

    public String getMetricUnit() {
        return FacesContextUtility.getOptionalRequestParameter(MetricComponent.UNIT, String.class, null);
    }

    @Override
    public void encodeBegin(FacesContext context, UIComponent component) throws IOException {
        ResponseWriter writer = context.getResponseWriter();
        MetricComponent metric = (MetricComponent) component;

        WebUser user = EnterpriseFacesContextUtility.getWebUser();
        MeasurementPreferences preferences = user.getMeasurementPreferences();
        MetricRangePreferences rangePreferences = preferences.getMetricRangePreferences();

        writer.startElement("b", null);
        writer.write("Metric Display Range:");
        writer.endElement("b");
        writer.write(" ");
        if (rangePreferences.readOnly) {
            writer.write(new Date(rangePreferences.begin) + " to " + new Date(rangePreferences.end));
            writer.write(" ");
        } else {
            writer.write("Last :");

            writer.write(" ");

            writer.startElement("select", metric);
            writer.writeAttribute("id", MetricComponent.VALUE, null);
            writer.writeAttribute("name", MetricComponent.VALUE, null);
            int lastN = rangePreferences.lastN;
            if (!timeIntervalValues.contains(Integer.valueOf(lastN))) {
                timeIntervalValues.add(lastN);
            }
            Collections.sort(timeIntervalValues);

            for (int timeIntervalOption : timeIntervalValues) {
                writer.startElement("option", metric);
                writer.writeAttribute("value", timeIntervalOption, MetricComponent.VALUE);
                if (timeIntervalOption == metric.getValue()) {
                    writer.writeAttribute("SELECTED", "SELECTED", null);
                }
                writer.write(String.valueOf(timeIntervalOption));
                writer.endElement("option");
            }
            writer.endElement("select");

            writer.write(" ");

            writer.startElement("select", metric);
            writer.writeAttribute("id", MetricComponent.UNIT, null);
            writer.writeAttribute("name", MetricComponent.UNIT, null);
            for (TimeUnit unit : metric.getUnitOptions()) {
                writer.startElement("option", metric);
                writer.writeAttribute("value", unit.name(), MetricComponent.UNIT);
                if (unit.name() == metric.getUnit()) {
                    writer.writeAttribute("SELECTED", "SELECTED", null);
                }
                writer.write(unit.getDisplayName());
                writer.endElement("option");
            }
            writer.endElement("select");

            writer.write(" ");
        }
        writer.write(" ");

        writer.startElement("a", null);
        writer.writeAttribute("href", "#", null);
        writer
            .writeAttribute(
                "onclick",
                "javascript:window.open('/rhq/common/metric/advanced.xhtml','Metric Display Range Settings','menubar=no,width=540,height=360,toolbar=no');",
                null);
        if (rangePreferences.readOnly) {
            writer.write("Edit Settings...");
        } else {
            writer.write("Advanced Settings...");
        }
        writer.endElement("a");
        if (rangePreferences.readOnly) {
            writer.write(" | ");
        }
    }
}
