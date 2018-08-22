package net.jgp.labs.sparkdq4ml;

import static org.apache.spark.sql.functions.callUDF;

import org.apache.spark.ml.linalg.Vector;
import org.apache.spark.ml.linalg.VectorUDT;
import org.apache.spark.ml.linalg.Vectors;
import org.apache.spark.ml.regression.LinearRegression;
import org.apache.spark.ml.regression.LinearRegressionModel;
import org.apache.spark.ml.regression.LinearRegressionTrainingSummary;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;

import net.jgp.labs.sparkdq4ml.dq.udf.MinimumPriceDataQualityUdf;
import net.jgp.labs.sparkdq4ml.dq.udf.PriceCorrelationDataQualityUdf;
import net.jgp.labs.sparkdq4ml.ml.udf.VectorBuilder;

public class DataQuality4MachineLearningApp {

  public static void main(String[] args) {
    DataQuality4MachineLearningApp app = new DataQuality4MachineLearningApp();
    app.start();
  }

  private void start() {
    SparkSession spark = SparkSession.builder().appName("DQ4ML").master("local")
        .getOrCreate();

    // DQ Section
    // ----------

    spark.udf().register("minimumPriceRule", new MinimumPriceDataQualityUdf(),
        DataTypes.DoubleType);
    spark.udf().register("priceCorrelationRule",
        new PriceCorrelationDataQualityUdf(), DataTypes.DoubleType);

    // Load our dataset
    String filename = "data/dataset-abstract.csv";
    Dataset<Row> df = spark.read().format("csv").option("inferSchema", "true")
        .option("header", "false")
        .load(filename);

    // simple renaming of the columns
    df = df.withColumn("guest", df.col("_c0")).drop("_c0");
    df = df.withColumn("price", df.col("_c1")).drop("_c1");

    System.out.println("----");
    System.out.println("Load & Format");
    df.show();
    System.out.println("----");

    // apply DQ rules
    // 1) min price
    df = df.withColumn("price_no_min", callUDF("minimumPriceRule", df.col(
        "price")));
    System.out.println("----");
    System.out.println("1st DQ rule");
    df.printSchema();
    df.show(50);
    System.out.println("----");

    df.createOrReplaceTempView("price");
    df = spark.sql(
        "SELECT cast(guest as int) guest, price_no_min AS price FROM price WHERE price_no_min > 0");
    System.out.println("----");
    System.out.println("1st DQ rule - clean-up");
    df.printSchema();
    df.show(50);
    System.out.println("----");

    // 2) correlated price
    df = df.withColumn("price_correct_correl", callUDF("priceCorrelationRule",
        df.col("price"), df.col("guest")));
    df.createOrReplaceTempView("price");
    df = spark.sql(
        "SELECT guest, price_correct_correl AS price FROM price WHERE price_correct_correl > 0");

    df.show(50);

    // ML Section
    // ----------

    spark.udf().register("vectorBuilder", new VectorBuilder(), new VectorUDT());

    df = df.withColumn("label", df.col("price"));
    df = df.withColumn("features", callUDF("vectorBuilder", df.col("guest")));
    df.printSchema();
    df.show();

    // Lots of complex ML code goes here

    LinearRegression lr = new LinearRegression()
        .setMaxIter(40)
        .setRegParam(1)
        .setElasticNetParam(1);

    // Fit the model to the data.
    LinearRegressionModel model = lr.fit(df);

    // Given a dataset, predict each point's label, and show the results.
    model.transform(df).show();

    LinearRegressionTrainingSummary trainingSummary = model.summary();
    System.out.println("numIterations: " + trainingSummary.totalIterations());
    System.out.println("objectiveHistory: " + Vectors.dense(trainingSummary
        .objectiveHistory()));
    trainingSummary.residuals().show();
    System.out.println("RMSE: " + trainingSummary.rootMeanSquaredError());
    System.out.println("r2: " + trainingSummary.r2());

    double intersect = model.intercept();
    System.out.println("Interesection: " + intersect);
    double regParam = model.getRegParam();
    System.out.println("Regression parameter: " + regParam);
    double tol = model.getTol();
    System.out.println("Tol: " + tol);
    Double feature = 40.0;
    Vector features = Vectors.dense(feature);
    double p = model.predict(features);

    System.out.println("Prediction for " + feature + " guests is " + p);
    System.out.println(8 * regParam + intersect);
  }
}
