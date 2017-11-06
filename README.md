:toc: macro
:toc-title:
:toclevels: 9

# A Sample of Lambda architecture project

The boilerplate project for detecting IoT sensor anomalies using the Lambda architecture.

## Requirements

Please install:

 - [Eclipse Mosquitto](https://mosquitto.org/) MQTT broker
 - [Apache Cassandra](http://cassandra.apache.org/) NoSQL database
 - [Redis](https://redis.io/) in-memory data store
 - [Apache Spark](https://spark.apache.org/) data processing engine
 - [SBT](http://www.scala-sbt.org/) build tool

## Usage

Configure the Cassandra data store:

    $ cqlsh -f resources/cql/schema.sql

*NOTE: For dropping the keyspace please use: `$ cqlsh -e "drop keyspace sandbox;"`*

Run the servers:

    $ mosquitto
    $ cassandra -f
    $ redis-server

Create the RAM disk and update the [configuration](scala/src/main/resources/application.conf)
to use it. In the example below we assume macOS, the attached disk name `/dev/disk2`
and the size of the disk is 100 Mb (100 * 2048):

    $ hdiutil attach -nomount ram://204800
    $ diskutil erasevolume HFS+ 'Spark' /dev/disk2

*NOTE: For deleting the RAM disk please use: `$ hdiutil detach /dev/disk2`*

Run the system (for the convenience, all microservices are packaged into the one system):

    $ sbt run

### IoT emulation

Modify the sensor values with the Producer: http://localhost:8081

Verify the messages by subscribing to the required MQTT topic:

    $ mosquitto_sub -t sensors/power

### Interactive processing

Verify the data stores with the Dashboard: http://localhost:8080

Verify the entries data store using CQL:

    $ cqlsh -e "select * from sandbox.entry limit 10;"

Dump the entries into the CSV file:

    $ cqlsh -e "copy sandbox.entry(sensor,ts,value,anomaly) to 'list.csv' with header=true;"

#### Fast analysis

An example REPL session with `sbt console`:

```scala
// Fix the borked REPL
jline.TerminalFactory.get.init

// Read the values from the CSV file
val iter = scala.io.Source.fromFile("list.csv").getLines

// Get the header
val header = iter.next.split(",")

// Get the data
val l = iter.map(_.split(",")).toList

// Get the sensor name for further analysis
val name = l.head(header.indexOf("sensor"))

// Get the first 200 values for the given sensor
val values = l.filter(_(0) == name).take(200).map(_(2).toDouble)

// Use the fast analyzer for the sample values
val samples = Seq(10, 200, -100)
samples.map(sample => analyzer.FastAnalyzer.getAnomaly(sample, values))
```

#### Full analysis

An example REPL session for the decision tree analysis with `spark-shell`.

Fit and save the model:

```scala
import org.apache.spark.ml.classification.DecisionTreeClassifier
import org.apache.spark.ml.feature.VectorAssembler

// Create the assembler to generate features vector
val assembler = new VectorAssembler().setInputCols(Array("value")).setOutputCol("features")

// Read the values from the CSV file
val l = spark.read.options(Map("header" -> "true", "inferSchema" -> "true")).csv("list.csv")

// Get the sensor name for further analysis
val sensorCol = l.schema.fieldIndex("sensor")
val name = l.first.getString(sensorCol)

// Get the first 1000 values for the given sensor
val filtered = l.filter(row => row.getString(sensorCol) == name).limit(1000)

// Create the model
val lr = new DecisionTreeClassifier().setLabelCol("anomaly")

// Fit the model
val model = lr.fit(assembler.transform(filtered))

// Save into the RAM disk
model.save("/Volumes/Spark/model")
```

Load and use the model:

```scala
import org.apache.spark.ml.classification.DecisionTreeClassificationModel
import org.apache.spark.ml.feature.VectorAssembler

// Create the assembler to generate features vector
val assembler = new VectorAssembler().setInputCols(Array("value")).setOutputCol("features")

// Load the model
val model = DecisionTreeClassifier.load("/Volumes/Spark/model")

// Prepare test data (the model ignores label value, can use any)
val samples = Seq(10, 200, -100)
val seq = samples.map(sample => (0.0, sample))
val t = spark.createDataFrame(seq).toDF("anomaly", "value")

// Make the predictions
val predictions = model.transform(assembler.transform(t))

// Show the probabilities
predictions.select("probability", "prediction").show(false)
```

### Processing Cluster

Verify the endpoint for anomaly detection:

    $ curl http://localhost:8082/

Check the latest analyzer snapshot:

    $ redis-cli hgetall fast-analysis

Verify the history of detecting anomalies using CQL:

    $ cqlsh -e "select * from sandbox.analysis limit 10;"
