package com.payflow.fraudservice.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline, one-time trainer for MockMlFraudScorer's logistic regression --
 * not part of the running service (no @Test methods, never executed by the
 * build). Run by hand (`java` directly, or paste into a scratch main) to
 * regenerate the weights pasted into application.yml's fraud.ml-scorer
 * block. Trains on synthetic data against a hand-written ground-truth rule,
 * since no real labeled fraud data exists in this project -- the point is
 * demonstrating the scoring/circuit-breaker integration pattern, not
 * producing a defensible fraud model.
 */
public class FraudModelTrainer {

    private record Sample(double amount, double velocity, double deviation, int isFraud) {
    }

    public static void main(String[] args) {
        Random random = new Random(42);
        List<Sample> samples = generateSyntheticSamples(random, 5000);
        double[] weights = train(samples, 0.1, 3000);

        System.out.println("amount:   " + weights[0]);
        System.out.println("velocity: " + weights[1]);
        System.out.println("deviation:" + weights[2]);
        System.out.println("bias:     " + weights[3]);
    }

    private static List<Sample> generateSyntheticSamples(Random random, int count) {
        List<Sample> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double amountCents = random.nextDouble() * 500_000; // up to $5,000
            double velocity = random.nextInt(10);                // checks in last 24h
            double deviation = random.nextDouble() * 3;          // multiples of prior average

            double normalizedAmount = amountCents / 500_000.0;
            double riskSignal = 1.1 * normalizedAmount + 0.35 * velocity + 0.9 * deviation
                    + random.nextGaussian() * 0.4;
            int label = riskSignal > 1.4 ? 1 : 0;
            samples.add(new Sample(amountCents, velocity, deviation, label));
        }
        return samples;
    }

    /** @return {amountWeight, velocityWeight, deviationWeight, bias} */
    private static double[] train(List<Sample> samples, double learningRate, int epochs) {
        double[] w = new double[4];
        for (int epoch = 0; epoch < epochs; epoch++) {
            double[] gradient = new double[4];
            for (Sample s : samples) {
                double normalizedAmount = s.amount() / 500_000.0;
                double z = w[0] * normalizedAmount + w[1] * s.velocity() + w[2] * s.deviation() + w[3];
                double predicted = 1.0 / (1.0 + Math.exp(-z));
                double error = predicted - s.isFraud();

                gradient[0] += error * normalizedAmount;
                gradient[1] += error * s.velocity();
                gradient[2] += error * s.deviation();
                gradient[3] += error;
            }
            for (int i = 0; i < 4; i++) {
                w[i] -= learningRate * gradient[i] / samples.size();
            }
        }
        return w;
    }
}
