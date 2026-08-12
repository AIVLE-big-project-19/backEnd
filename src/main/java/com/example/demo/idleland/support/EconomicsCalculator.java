package com.example.demo.idleland.support;

public final class EconomicsCalculator {

    public static final long ROOF_INSTALLATION_COST_PER_KW = 1_300_000L;
    public static final long LAND_INSTALLATION_COST_PER_KW = 1_200_000L;
    public static final long PARKING_LOT_INSTALLATION_COST_PER_KW = 1_500_000L;
    public static final double ANNUAL_OM_RATE = 0.015d;
    public static final long REVENUE_PER_KWH = 160L;

    private EconomicsCalculator() {
    }

    public static long installationCostPerKw(String registeredType) {
        return switch (registeredType) {
            case "ROOF" -> ROOF_INSTALLATION_COST_PER_KW;
            case "PARKING_LOT" -> PARKING_LOT_INSTALLATION_COST_PER_KW;
            default -> LAND_INSTALLATION_COST_PER_KW;
        };
    }


    public static Long resolveAnnualRevenueKrw(Long annualGenerationKwh, Long annualRevenueKrw) {
        if (annualRevenueKrw != null) {
            return annualRevenueKrw;
        }
        return annualGenerationKwh == null ? null : annualGenerationKwh * REVENUE_PER_KWH;
    }

    public record RoiResult(Long installationCost, Long annualOmCost, Long annualNetIncome,
                             Double roiPercent, Double paybackYears) {
    }

    public static RoiResult calculateRoi(String registeredType, Integer capacityKw, Long annualRevenueKrw) {
        if (capacityKw == null) {
            return new RoiResult(null, null, null, null, null);
        }

        long installationCost = capacityKw * installationCostPerKw(registeredType);
        long annualOmCost = Math.round(installationCost * ANNUAL_OM_RATE);
        Long annualNetIncome = annualRevenueKrw == null ? null : annualRevenueKrw - annualOmCost;

        Double roiPercent = null;
        Double paybackYears = null;
        if (annualNetIncome != null && annualNetIncome > 0) {
            roiPercent = roundOneDecimal(annualNetIncome / (double) installationCost * 100d);
            paybackYears = roundOneDecimal(installationCost / (double) annualNetIncome);
        }

        return new RoiResult(installationCost, annualOmCost, annualNetIncome, roiPercent, paybackYears);
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
