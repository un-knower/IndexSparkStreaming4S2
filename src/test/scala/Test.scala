
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import scala.io.Source
import com.yisa.sparkstreaming.model.PassInfoForKafkaNewInterface
import com.google.gson.reflect.TypeToken
import com.google.gson.Gson

object Test {
  def main(args: Array[String]) {
    //    val path = new File("E://id_rsa.pub");
    //    println("我们要处理的路径为：" + path)
    //
    //    if (path.exists()) {
    //      print(path.getPath);

    for (line <- Source.fromFile("E://moma/json1.txt").getLines) {
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
          println(ex.printStackTrace())
          println(ex.getMessage)
          println("json数据接收异常 ：" + line)
        }
      }
      
      println(passInfo2)
    }

  }

  def getDate(x: String): Date = {

    val format = new SimpleDateFormat("yyyyMMddHHmmss")

    var d = new Date()
    try {
      if (x == "")
        return null
      else {
        d = format.parse(x);
        return d
      }
    } catch {
      case e: Exception => println("cdr parse timestamp wrong")
    }
    return null
  }

  def getdate2(x: String): String = {
    //    20160523140222
    var dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss")
    var date2: Date = dateFormat.parse(x);

    var dateFormat2: SimpleDateFormat = new SimpleDateFormat("yyyyMMdd")
    var dateString: String = dateFormat2.format(date2);

    return dateString;
  }

  def getdate(x: Int): String = {
    var dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyyMMdd")
    var cal: Calendar = Calendar.getInstance()
    cal.add(Calendar.DATE, x)
    dateFormat.format(cal.getTime)
  }
}