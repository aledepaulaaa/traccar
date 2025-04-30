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

    // private Collection<SummaryReportItem> calculateDeviceResult(
    // Device device, Date from, Date to, boolean fast) throws StorageException {

    // SummaryReportItem result = new SummaryReportItem();
    // result.setDeviceId(device.getId());
    // result.setDeviceName(device.getName());

    // Position first = null;
    // Position last = null;
    // if (fast) {
    // first = getEdgePosition(device.getId(), from, to, false);
    // last = getEdgePosition(device.getId(), from, to, true);
    // } else {
    // var positions = PositionUtil.getPositions(storage, device.getId(), from, to);
    // for (Position position : positions) {
    // if (first == null) {
    // first = position;
    // }
    // if (position.getSpeed() > result.getMaxSpeed()) {
    // result.setMaxSpeed(position.getSpeed());
    // }
    // last = position;
    // }
    // }

    // if (first != null && last != null) {
    // TripsConfig tripsConfig = new TripsConfig(
    // new AttributeUtil.StorageProvider(config, storage, permissionsService,
    // device));
    // boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
    // result.setDistance(PositionUtil.calculateDistance(first, last,
    // !ignoreOdometer));
    // result.setSpentFuel(reportUtils.calculateFuel(first, last));

    // if (first.hasAttribute(Position.KEY_HOURS) &&
    // last.hasAttribute(Position.KEY_HOURS)) {
    // result.setStartHours(first.getLong(Position.KEY_HOURS));
    // result.setEndHours(last.getLong(Position.KEY_HOURS));
    // long engineHours = result.getEngineHours();
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

    // return List.of();
    // }

    private Collection<SummaryReportItem> calculateDeviceResult(
            Device device, Date from, Date to, boolean fast) throws StorageException {

        SummaryReportItem result = new SummaryReportItem();
        result.setDeviceId(device.getId());
        result.setDeviceName(device.getName());

        Position first = null;
        Position last = null;
        double calculatedMaxSpeed = 0.0; // Variável local para guardar maxSpeed

        if (fast) {
            // Modo rápido: Pega apenas a primeira e última posição. MaxSpeed NÃO é
            // calculado realisticamente.
            first = getEdgePosition(device.getId(), from, to, false);
            last = getEdgePosition(device.getId(), from, to, true);
            if (first != null) {
                calculatedMaxSpeed = first.getSpeed(); // Usa a velocidade da primeira posição como uma aproximação
                                                       // pobre
            }
            if (last != null && last.getSpeed() > calculatedMaxSpeed) {
                calculatedMaxSpeed = last.getSpeed(); // Ou da última, se for maior
            }
            // Define no resultado, mesmo que não seja o máximo real do período
            result.setMaxSpeed(calculatedMaxSpeed);

        } else {
            // Modo não-rápido: Itera por todas as posições para calcular MaxSpeed real.
            var positions = PositionUtil.getPositions(storage, device.getId(), from, to);
            for (Position position : positions) {
                if (first == null) {
                    first = position;
                }
                // Calcula o maxSpeed real iterando pelas posições
                if (position.getSpeed() > calculatedMaxSpeed) {
                    calculatedMaxSpeed = position.getSpeed();
                }
                last = position;
            }
            // Define o maxSpeed real calculado no resultado
            result.setMaxSpeed(calculatedMaxSpeed);
        }

        if (first != null && last != null) {
            // Define startTime e endTime primeiro, pois são usados no fallback de
            // engineHours
            result.setStartTime(first.getFixTime());
            result.setEndTime(last.getFixTime());

            // Calcula distância
            TripsConfig tripsConfig = new TripsConfig(
                    new AttributeUtil.StorageProvider(config, storage, permissionsService, device));
            boolean ignoreOdometer = tripsConfig.getIgnoreOdometer();
            result.setDistance(PositionUtil.calculateDistance(first, last, !ignoreOdometer)); // Assume retorno em
                                                                                              // metros

            // --- Lógica Corrigida para Engine Hours ---
            long engineHoursDurationSeconds = 0;

            // 1. Tenta usar Position.KEY_HOURS (assumindo milissegundos)
            if (first.hasAttribute(Position.KEY_HOURS) && last.hasAttribute(Position.KEY_HOURS)) {
                long firstHoursMs = first.getLong(Position.KEY_HOURS);
                long lastHoursMs = last.getLong(Position.KEY_HOURS);
                // Guarda os valores originais apenas para referência, se necessário no frontend
                // A duração será representada pela diferença controlada abaixo
                // result.setStartHours(firstHoursMs);
                // result.setEndHours(lastHoursMs);
                if (lastHoursMs > firstHoursMs) {
                    engineHoursDurationSeconds = (lastHoursMs - firstHoursMs) / 1000; // Converte para segundos
                }
            }

            // 2. Fallback: Usa a diferença de tempo do FixTime se KEY_HOURS não deu
            // resultado > 0
            if (engineHoursDurationSeconds <= 0) {
                long fixTimeDifferenceSeconds = (result.getEndTime().getTime() - result.getStartTime().getTime())
                        / 1000;
                if (fixTimeDifferenceSeconds > 0) {
                    engineHoursDurationSeconds = fixTimeDifferenceSeconds;
                }
            }

            // IMPORTANTE: Define startHours e endHours de forma que getEngineHours()
            // (assumindo que calcula end - start) retorne a duração em MILISSEGUNDOS
            // correspondente a engineHoursDurationSeconds. Isso garante que o valor
            // serializado para o frontend como 'engineHours' esteja efetivamente em
            // segundos.
            // Se getEngineHours() funcionar diferente, esta parte precisa ser adaptada.
            result.setStartHours(0L); // Define uma base
            result.setEndHours(engineHoursDurationSeconds * 1000); // Define o fim baseado na duração em segundos * 1000

            System.out.println("DEBUG - Device: " + device.getId()
                    + ", calculatedEngineHours (seconds): " + engineHoursDurationSeconds);

            // --- Lógica Corrigida para Average Speed ---
            if (engineHoursDurationSeconds > 0) {
                // distance (metros) / duration (segundos) = m/s
                double speedMps = result.getDistance() / engineHoursDurationSeconds;
                result.setAverageSpeed(UnitsConverter.knotsFromMps(speedMps)); // Converte m/s para knots
            } else if (result.getDistance() > 0) {
                // Fallback MUITO simples se houve distância mas não duração.
                // Usar maxSpeed só é razoável se fast = false.
                // Poderia retornar 0 ou um valor configurável. Vamos usar maxSpeed/2 se não for
                // fast.
                if (!fast) {
                    result.setAverageSpeed(result.getMaxSpeed() / 2.0);
                } else {
                    result.setAverageSpeed(0.0); // Fallback mais seguro no modo fast
                }
            } else {
                result.setAverageSpeed(0.0); // Sem distância, sem velocidade média
            }

            // --- Lógica para Spent Fuel com Fallback ---
            double calculatedFuel = reportUtils.calculateFuel(first, last); // Assume retorno em mL
            if (calculatedFuel == 0 && result.getDistance() > 0) {
                // Fallback: Usa fator de consumo (ex: 0.1 L/km) e distância (metros)
                // (distance_meters / 1000.0) * 0.1 L/km * 1000 mL/L = distance_meters * 0.1 mL
                double consumptionFactorLK = 0.1; // Exemplo: 0.1 Litros por KM. Coloque em config se possível.
                calculatedFuel = result.getDistance() * consumptionFactorLK; // Resultado em mL
                System.out.println("DEBUG - Device: " + device.getId()
                        + ", spentFuel using fallback calculation. Distance (m): " + result.getDistance()
                        + ", Factor (L/km): " + consumptionFactorLK + ", Calculated Fuel (mL): " + calculatedFuel);
            }
            result.setSpentFuel(calculatedFuel);

            // --- Lógica do Odômetro ---
            if (!ignoreOdometer
                    && first.hasAttribute(Position.KEY_ODOMETER) && last.hasAttribute(Position.KEY_ODOMETER)
                    && first.getDouble(Position.KEY_ODOMETER) != 0 && last.getDouble(Position.KEY_ODOMETER) != 0) {
                result.setStartOdometer(first.getDouble(Position.KEY_ODOMETER));
                result.setEndOdometer(last.getDouble(Position.KEY_ODOMETER));
            } else {
                // Fallback para KEY_TOTAL_DISTANCE se KEY_ODOMETER não estiver disponível ou
                // for zero
                result.setStartOdometer(first.getDouble(Position.KEY_TOTAL_DISTANCE));
                result.setEndOdometer(last.getDouble(Position.KEY_TOTAL_DISTANCE));
            }

            // startTime e endTime já foram definidos no início deste bloco
            return List.of(result);
        }

        // Retorna lista vazia se first ou last for null
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