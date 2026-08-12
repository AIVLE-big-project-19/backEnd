package com.example.demo.idleland.support;

import java.util.List;


public final class PercentileCalculator {

    private PercentileCalculator() {
    }

    public static Double percentile(List<Integer> population, Integer value) {
        if (value == null || population == null || population.isEmpty()) {
            return null;
        }

        long less = population.stream().filter(v -> v != null && v < value).count();
        long equal = population.stream().filter(v -> v != null && v.intValue() == value).count();
        if (less + equal == 0) {
            return null;
        }

        double averageRank = less + (equal + 1) / 2.0;
        return averageRank / population.size();
    }

    // Double 버전. DB에 저장된 solarReadinessScore 분포 기준으로 등급을 다시 계산할 때 쓴다.
    public static Double percentile(List<Double> population, Double value) {
        if (value == null || population == null || population.isEmpty()) {
            return null;
        }

        long less = population.stream().filter(v -> v != null && v < value).count();
        long equal = population.stream().filter(v -> v != null && v.doubleValue() == value).count();
        if (less + equal == 0) {
            return null;
        }

        double averageRank = less + (equal + 1) / 2.0;
        return averageRank / population.size();
    }


    public static String gradeFromPercentile(double percentile) {
        if (percentile >= 0.80d) {
            return "A";
        }
        if (percentile >= 0.50d) {
            return "B";
        }
        return "C";
    }
}
