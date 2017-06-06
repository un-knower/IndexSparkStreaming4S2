package com.yisa.sparkstreaming.engine

import java.text.SimpleDateFormat
import java.util.Date
import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.MissingOptionException
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import org.apache.commons.cli.PosixParser
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.StreamingContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yisa.sparkstreaming.manager.KafkaManager
import com.yisa.sparkstreaming.model.PassInfo2
import com.yisa.sparkstreaming.model.PassinfoForHive3
import com.yisa.sparkstreaming.source.Config10
import kafka.serializer.StringDecoder
import org.apache.spark.sql.SaveMode
import org.apache.spark.streaming.Seconds
import org.apache.hadoop.hbase.TableName
import java.util.ArrayList
import org.apache.hadoop.hbase.client.Put
import java.util.UUID
import org.apache.hadoop.hbase.util.Bytes
import scala.util.Random
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.apache.hadoop.hbase.client.BufferedMutator
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.HConstants
import org.apache.hadoop.hbase.mapreduce.MultiTableOutputFormat
import org.apache.hadoop.mapred.JobConf
import com.yisa.sparkstreaming.source.RedisClusterClientZK
import redis.clients.jedis.JedisCluster
import com.yisa.wifi.zookeeper.ZookeeperUtil
import com.yisa.sparkstreaming.model.PassInfoForKafkaNewInterface

/**
 * 低级Api实现
 */
object DataToIndexPre {

  def main(args: Array[String]) {

    var cmd: CommandLine = null

    val options: Options = new Options()

    try {

      var zkServer: Option = new Option("zk_server", true, "输入zookeeper服务器地址")

      zkServer.setRequired(true)

      options.addOption(zkServer)

      val parser: PosixParser = new PosixParser()

      cmd = parser.parse(options, args)

    } catch {

      case ex: MissingOptionException => {

        println(ex)

        println("--zk_server <value> : " + options.getOption("zk_server").getDescription)

        System.exit(1)
      }
    }

    var zk_server_str = ""
    if (cmd != null && cmd.hasOption("zk_server")) {
      zk_server_str = cmd.getOptionValue("zk_server")
    }

    Config10.initConfig(zk_server_str)

    var sparkConf = new SparkConf().setAppName("DataToIndexPre")
    sparkConf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    //    sparkConf.set("spark.ui.port", Config10.SPARK_UI_PORT)

    var sc = new SparkContext(sparkConf)
    sc.hadoopConfiguration.set("hbase.zookeeper.quorum", zk_server_str)
    sc.hadoopConfiguration.set("hbase.zookeeper.property.clientPort", "2181")
    var ssc = new StreamingContext(sc, Seconds(5))

    println("低级API实现")
    println(Config10.showIndexString())
    println("Spark Streaming从每个分区，每秒读取的数据量 : " + sc.getConf.get("spark.streaming.kafka.maxRatePerPartition"))

    val zkUtil = new ZookeeperUtil()
    val configs = zkUtil.getAllConfig(zk_server_str, "spark_engine", false)
    val PASS_INFO_INDEX_TABLE = configs.get("pass_info_index_table")
    val PASS_YEARID_INDEX_TABLE = configs.get("pass_yearid_index_table")

    val configs_spark_index = zkUtil.getAllConfig(zk_server_str, "spark_index", false)

    val topicsSet = configs_spark_index.get("preKafkaProducerTopicName").split(",").toSet
    val kafkaParams = Map[String, String](
      "metadata.broker.list" -> Config10.KAFKA_BROKER_ID,
      "group.id" -> configs.get("KAFKA_GROUP_ID_INDEX"),
      //      "group.id" -> "gid201704141528001", 
      "auto.offset.reset" -> "largest")

    // largest/smallest

    val useRedisOrNot = Config10.configs.get("useRedisOrNot").toBoolean
    println("useRedisOrNot:" + useRedisOrNot)

    val km = new KafkaManager(kafkaParams)

    val kafkaDStreams = km.createDirectStream[String, String, StringDecoder, StringDecoder](
      ssc, kafkaParams, topicsSet)

    kafkaDStreams.foreachRDD(rdd => {
      if (!rdd.isEmpty()) {

        var PassinfoForHive3_RDD = rdd.map(line_data => {

          var line = line_data._2
          var passInfo2: PassInfoForKafkaNewInterface = null

          try {
            val gson = new Gson
            val mapType = new TypeToken[PassInfoForKafkaNewInterface] {}.getType
            passInfo2 = gson.fromJson[PassInfoForKafkaNewInterface](line, mapType)

            var format2 = new SimpleDateFormat("yyyyMMdd")
            val dateid = format2.format(new Date((passInfo2.captureTime + "000").toLong)).toInt
            passInfo2.dateid = dateid

          } catch {
            case ex: Exception => {
              println("json数据接收异常 ：" + line)
            }
          }

          if (passInfo2 == null) {
            println("json数据接收异常 ：" + line)
            throw new Exception("json数据接收异常")
          } else {
            passInfo2.levelId = if (passInfo2.levelId == -1) 0 else passInfo2.levelId
            passInfo2.yearId = if (passInfo2.yearId == -1) 0 else passInfo2.yearId
            passInfo2.modelId = if (passInfo2.modelId == -1) 0 else passInfo2.modelId
            passInfo2.brandId = if (passInfo2.brandId == -1) 0 else passInfo2.brandId
          }
          passInfo2

        }).filter(_!=null)

        //          sc.hadoopConfiguration.set(TableOutputFormat.OUTPUT_TABLE, tablename)

        val job = new Job(sc.hadoopConfiguration)
        job.setOutputKeyClass(classOf[ImmutableBytesWritable])
        job.setOutputValueClass(classOf[Put])
        job.setOutputFormatClass(classOf[MultiTableOutputFormat])

        //        val hConf = HBaseConfiguration.create()
        //        hConf.set(HConstants.ZOOKEEPER_QUORUM, zk_server_str)

        //        val jobConf = new JobConf(hConf, this.getClass)

        val pass_info_index_RDD = PassinfoForHive3_RDD.map(passinfoForHive3 => {
          convert_Pass_info_index(passinfoForHive3, PASS_INFO_INDEX_TABLE)
        })
        pass_info_index_RDD.saveAsNewAPIHadoopDataset(job.getConfiguration())

        val result_withRecfeature = PassinfoForHive3_RDD.filter(f => { f.feature != null && f.feature != "" })
        val pass_yearid_index_RDD = result_withRecfeature.map(pass_info => {
          convert_Pass_yearid_index(pass_info, zk_server_str, useRedisOrNot, PASS_YEARID_INDEX_TABLE)
        });
        pass_yearid_index_RDD.saveAsNewAPIHadoopDataset(job.getConfiguration())

        //        val All_RDD = pass_info_index_RDD.union(pass_yearid_index_RDD)

        //        All_RDD.saveAsNewAPIHadoopFile("", classOf[ImmutableBytesWritable], classOf[Put], classOf[MultiTableOutputFormat], jobConf)
        // 再更新offsets
        km.updateZKOffsets(rdd)

      }
      val formatRdd = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      var nowDate = formatRdd.format(new Date())
      println(nowDate + " rdd的长度 ：" + rdd.count())
    })

    ssc.start()
    ssc.awaitTermination()
  }
  // 在 HBase 中表的 schema 一般是这样的
  // *row   cf:col_1    cf:col_2
  // 而在Spark中，我们操作的是RDD元组，yearid_platenumber_direction_colorid_modelid_brandid_levelid_lastcaptured_issheltered_solrid
  // 我们需要将 *RDD[(uid:Int, name:String, age:Int)]* 转换成 *RDD[(ImmutableBytesWritable, Put)]*
  // 我们定义了 convert 函数做这个转换工作
  def convert_Pass_info_index(triple: PassInfoForKafkaNewInterface, PASS_INFO_INDEX_TABLE: String) = {
    //    val tablename = "pass_info_index2"
    val tablename = PASS_INFO_INDEX_TABLE

    var input = triple
    var rowkey = triple.locationuuid + "_" + triple.captureTime
    var values = triple.yearId + "_" + triple.plateNumber + "_" + triple.directionId + "_" + triple.colorId + "_" + triple.modelId + "_" + triple.brandId + "_" + triple.levelId + "_" + triple.lastCaptured + "_" + triple.sunShadeRight + "_" + triple.id

    val random = new Random();
    var num = random.nextInt(999)
    var numS = num.toString();
    if (numS.length() == 1) {
      numS = "00" + numS
    } else if (numS.length() == 2) {
      numS = "0" + numS
    }

    var yearid = triple.yearId.toString();
    if (yearid.length() == 1) {
      yearid = "000" + yearid
    } else if (yearid.length() == 2) {
      yearid = "00" + yearid
    } else if (yearid.length() == 3) {
      yearid = "0" + yearid
    }

    var version = (new Date().getTime / 1000 + yearid + numS).toLong
    var p = new Put(Bytes.toBytes(rowkey))
    p.addColumn(Bytes.toBytes("info"), Bytes.toBytes("pass"), version, Bytes.toBytes(values))
    (new ImmutableBytesWritable(Bytes.toBytes(tablename)), p)
  }

  def convert_Pass_yearid_index(triple: PassInfoForKafkaNewInterface, zk_server_str: String, useRedisOrNot: Boolean, PASS_YEARID_INDEX_TABLE: String) = {

    if (useRedisOrNot) {

      val jedis = RedisClusterClientZK.getJedisCluster(zk_server_str)
      try {
        jedis.lpush(triple.dateid + "_" + triple.yearId, triple.feature + "_" + triple.plateNumber + "_" + triple.id + "_" + triple.captureTime)
      } catch {
        case ex: Exception => {
          ex.printStackTrace()
          println(ex.printStackTrace())
          println(triple.dateid + "_" + triple.yearId, triple.feature + "_" + triple.plateNumber + "_" + triple.id + "_" + triple.captureTime)
        }
      }
    }

    //    val tablename = "pass_yearid_index"
    val tablename = PASS_YEARID_INDEX_TABLE

    //-------------start insert hbase---------------------
    val date = new Date((triple.captureTime.toString() + "000").toLong)
    val format = new SimpleDateFormat("yyyyMMddHH")
    val timeid = format.format(date)

    val sBuilder = new StringBuilder();
    sBuilder.append(triple.yearId).append("_").append(timeid);
    val id = sBuilder.toString();
    val rowkey_uuid = UUID.nameUUIDFromBytes(id.getBytes()).toString().replaceAll("-", "");
    val put_pass_yearid_index = new Put(Bytes.toBytes(rowkey_uuid))

    //
    val byteData = Base64.getDecoder.decode(triple.feature)
    var numRec: Long = 0
    for (i <- 0 until byteData.length) {
      var n = (byteData(i) & 0xff)
      numRec += n
    }
    var numRec2 = numRec % 1000
    var numRec2S = numRec2.toString()
    if (numRec2S.length() == 1) {
      numRec2S = "00" + numRec2
    } else if (numRec2S.length() == 2) {
      numRec2S = "0" + numRec2
    }

    val random = new Random();
    var num = random.nextInt(999)
    var numS = num.toString();
    if (numS.length() == 1) {
      numS = "00" + numS
    } else if (numS.length() == 2) {
      numS = "0" + numS
    }

    put_pass_yearid_index.addColumn(Bytes.toBytes("info"), Bytes.toBytes("yearids"), (triple.captureTime.toString() + numS + numRec2S).toLong, Bytes.toBytes(triple.feature + "_" + triple.plateNumber + "_" + triple.id + "_" + triple.captureTime))

    (new ImmutableBytesWritable(Bytes.toBytes(tablename)), put_pass_yearid_index)
  }

}