/*
 * Copyright 2016 - 2024 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.reports;

import org.jxls.util.JxlsHelper;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.helper.model.UserUtil;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.reports.common.ReportUtils;
import org.traccar.reports.common.TripsConfig;
import org.traccar.reports.model.SummaryReportItem;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class SummaryReportProvider {

    private final Config config;
    private final ReportUtils reportUtils;
    private final PermissionsService permissionsService;
    private final Storage storage;

    @Inject
    public SummaryReportProvider(
            Config config, ReportUtils reportUtils, PermissionsService permissionsService, Storage storage) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.permissionsService = permissionsService;
        this.storage = storage;
    }

    private Position getEdgePosition(long deviceId, Date from, Date to, boolean end) throws StorageException {
        return storage.getObject(Position.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Between("fixTime", "from", from, "to", to)),
                new Order("fixTime", end, 1)));
    }

    private Collection<SummaryReportItem> calculateDeviceResult(
            Device device, Date from, Date to, boolean fast) throws StorageException {

        SummaryReportItem result = new SummaryReportItem();
        result.setDeviceId(device.getId());
        result.setDeviceName(device.getName());

        Position first = null;
        Position last = null;
        if (fast) {
            first = getEdgePosition(device.getId(), from, to, false);
            last = getEdgePosition(device.getId(), from, to, true);
        } else {
            var positions = PositionUtil.getPositions(storage, device.getId(), from, to);
            for (Position position : positions) {
                if (first == null) {
                    first = position;
                }
                if (position.getSpeed() > result.getMaxSpeed()) {
                    result.setMaxSpeed(position.getSpeed());
                }
                last = position;
            }
        }

        if (first != null && last != null) {
            TripsConfig tripsConfig = new TripsConfig(
                    new AttributeUtil.StorageProvider(config, storage, permissionsService, device));
            boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
            result.setDistance(PositionUtil.calculateDistance(first, last, !ignoreOdometer));

            // Cálculo de combustível com fallback
            double calculatedFuel = reportUtils.calculateFuel(first, last);
            if (calculatedFuel == 0 && result.getDistance() > 0) {
                // Calcular combustível baseado na distância usando um fator de consumo médio
                // Por exemplo, 0.1 litros por quilômetro (ajuste conforme necessário)
                calculatedFuel = result.getDistance() * 0.1;
            }
            result.setSpentFuel(calculatedFuel);

            // Adicionar log aqui
            System.out.println("DEBUG - Device: " + device.getId()
                    + ", distance: " + result.getDistance()
                    + ", spentFuel: " + result.getSpentFuel()
                    + ", maxSpeed: " + result.getMaxSpeed());

            if (first.hasAttribute(Position.KEY_HOURS) && last.hasAttribute(Position.KEY_HOURS)) {
                long firstHours = first.getLong(Position.KEY_HOURS);
                long lastHours = last.getLong(Position.KEY_HOURS);
                result.setStartHours(firstHours);
                result.setEndHours(lastHours);
                long engineHours = result.getEngineHours();

                // Se engineHours for zero, calcular baseado na diferença de tempo
                if (engineHours == 0 && result.getStartTime() != null && result.getEndTime() != null) {
                    // Calcular a diferença em segundos
                    long diffInSeconds = (result.getEndTime().getTime() - result.getStartTime().getTime()) / 1000;
                    // // Converter para horas (em segundos)
                    // engineHours = diffInSeconds;
                    // Atualizar o engineHours no resultado definindo endHours
                    result.setEndHours(result.getStartHours() + diffInSeconds);
                    // Recalcular engineHours
                    engineHours = result.getEngineHours();
                    // result.setEndHours(result.getStartHours() + engineHours);
                }

                System.out.println("DEBUG - Device: " + device.getId()
                        + ", calculatedEngineHours: " + engineHours);

                // Calcular velocidade média mesmo se engineHours for zero
                // Para averageSpeed
                if (engineHours > 0) {
                    result.setAverageSpeed(UnitsConverter.knotsFromMps(result.getDistance() * 1000 / engineHours));
                } else if (result.getDistance() > 0) {
                    // Fallback para quando engineHours é zero
                    result.setAverageSpeed(result.getMaxSpeed() / 2);
                }
            }

            if (!ignoreOdometer
                    && first.getDouble(Position.KEY_ODOMETER) != 0 && last.getDouble(Position.KEY_ODOMETER) != 0) {
                result.setStartOdometer(first.getDouble(Position.KEY_ODOMETER));
                result.setEndOdometer(last.getDouble(Position.KEY_ODOMETER));
            } else {
                result.setStartOdometer(first.getDouble(Position.KEY_TOTAL_DISTANCE));
                result.setEndOdometer(last.getDouble(Position.KEY_TOTAL_DISTANCE));
            }

            result.setStartTime(first.getFixTime());
            result.setEndTime(last.getFixTime());
            return List.of(result);
        }

        // if (first != null && last != null) {
        // TripsConfig tripsConfig = new TripsConfig(
        // new AttributeUtil.StorageProvider(config, storage, permissionsService,
        // device));
        // boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
        // result.setDistance(PositionUtil.calculateDistance(first, last,
        // !ignoreOdometer));
        // result.setSpentFuel(reportUtils.calculateFuel(first, last));

        // // Adicionar log aqui
        // System.out.println("DEBUG - Device: " + device.getId()
        // + ", distance: " + result.getDistance()
        // + ", spentFuel: " + result.getSpentFuel()
        // + ", maxSpeed: " + result.getMaxSpeed());

        // if (first.hasAttribute(Position.KEY_HOURS) &&
        // last.hasAttribute(Position.KEY_HOURS)) {
        // long firstHours = first.getLong(Position.KEY_HOURS);
        // long lastHours = last.getLong(Position.KEY_HOURS);
        // result.setStartHours(firstHours);
        // result.setEndHours(lastHours);
        // long engineHours = result.getEngineHours();

        // // Adicionar log aqui também
        // System.out.println("DEBUG - Device: " + device.getId()
        // + ", calculatedEngineHours: " + engineHours);
        // if (engineHours > 0) {
        // result.setAverageSpeed(UnitsConverter.knotsFromMps(result.getDistance() *
        // 1000 / engineHours));
        // }
        // }

        // if (!ignoreOdometer
        // && first.getDouble(Position.KEY_ODOMETER) != 0 &&
        // last.getDouble(Position.KEY_ODOMETER) != 0) {
        // result.setStartOdometer(first.getDouble(Position.KEY_ODOMETER));
        // result.setEndOdometer(last.getDouble(Position.KEY_ODOMETER));
        // } else {
        // result.setStartOdometer(first.getDouble(Position.KEY_TOTAL_DISTANCE));
        // result.setEndOdometer(last.getDouble(Position.KEY_TOTAL_DISTANCE));
        // }

        // result.setStartTime(first.getFixTime());
        // result.setEndTime(last.getFixTime());
        // return List.of(result);
        // }

        return List.of();
    }

    private Collection<SummaryReportItem> calculateDeviceResults(
            Device device, ZonedDateTime from, ZonedDateTime to, boolean daily) throws StorageException {

        boolean fast = Duration.between(from, to).toSeconds() > config.getLong(Keys.REPORT_FAST_THRESHOLD);
        var results = new ArrayList<SummaryReportItem>();
        if (daily) {
            while (from.truncatedTo(ChronoUnit.DAYS).isBefore(to.truncatedTo(ChronoUnit.DAYS))) {
                ZonedDateTime fromDay = from.truncatedTo(ChronoUnit.DAYS);
                ZonedDateTime nextDay = fromDay.plusDays(1);
                results.addAll(calculateDeviceResult(
                        device, Date.from(from.toInstant()), Date.from(nextDay.toInstant()), fast));
                from = nextDay;
            }
        }
        results.addAll(calculateDeviceResult(device, Date.from(from.toInstant()), Date.from(to.toInstant()), fast));
        return results;
    }

    public Collection<SummaryReportItem> getObjects(
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to, boolean daily) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        var tz = UserUtil.getTimezone(permissionsService.getServer(), permissionsService.getUser(userId)).toZoneId();

        ArrayList<SummaryReportItem> result = new ArrayList<>();
        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            var deviceResults = calculateDeviceResults(
                    device, from.toInstant().atZone(tz), to.toInstant().atZone(tz), daily);
            for (SummaryReportItem summaryReport : deviceResults) {
                if (summaryReport.getStartTime() != null && summaryReport.getEndTime() != null) {
                    // Adicionar log aqui
                    System.out.println("FINAL REPORT - Device: " + device.getId()
                            + ", deviceName: " + summaryReport.getDeviceName()
                            + ", startHours: " + summaryReport.getStartHours()
                            + ", endHours: " + summaryReport.getEndHours()
                            + ", engineHours: " + summaryReport.getEngineHours()
                            + ", averageSpeed: " + summaryReport.getAverageSpeed()
                            + ", maxSpeed: " + summaryReport.getMaxSpeed()
                            + ", spentFuel: " + summaryReport.getSpentFuel());
                    result.add(summaryReport);
                }
            }
        }
        return result;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to, boolean daily) throws StorageException, IOException {
        Collection<SummaryReportItem> summaries = getObjects(userId, deviceIds, groupIds, from, to, daily);

        File file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export", "summary.xlsx").toFile();
        try (InputStream inputStream = new FileInputStream(file)) {
            var context = reportUtils.initializeContext(userId);
            context.putVar("summaries", summaries);
            context.putVar("from", from);
            context.putVar("to", to);
            JxlsHelper.getInstance().setUseFastFormulaProcessor(false)
                    .processTemplate(inputStream, outputStream, context);
        }
    }
}
