package ddbt.lib
import java.util.Date;

/**
 * These are user-library functions, prefixed with 'U' (to avoid name clashes).
 * They are user as 'built-in' functions from the M3 language and appropriate
 * calls are generated.
 *
 * @TODO: For further optimization, these can be immediately inlined in the
 * generated code instead of being shipped apart (their body is very small).
 *
 * @author TCK
 */

object Functions {
  import scala.language.implicitConversions
  // Implicit conversions
  implicit def boolConv(b:Boolean):Long = if (b) 1L else 0L
  implicit def dateOps(d:Date):DateWrapper = new DateWrapper(d)
  class DateWrapper(d:Date) {
    def < (d2:Date) = d.getTime < d2.getTime
    def > (d2:Date) = d.getTime > d2.getTime
    def <= (d2:Date) = d.getTime <= d2.getTime
    def >= (d2:Date) = d.getTime >= d2.getTime
  }

  // User functions that can be called using apply
  // These are prefixed by U (user) to avoid name clash with possible other functions
  def Udate(str:String):Date = {
    val s=str.split("-")
    new java.util.GregorianCalendar(s(0).toInt,s(1).toInt - 1,s(2).toInt).getTime();
  }

  private val re_cache = new java.util.HashMap[String, java.util.regex.Pattern]()
  def Uregexp_match(re:String, str:String): Long = if ((re_cache.get(re) match {
    case null => val p=java.util.regex.Pattern.compile(re); re_cache.put(re,p); p
    case p => p
  }).matcher(str).find) 1L else 0L
  def Upreg_match(p:java.util.regex.Pattern, str:String): Long = if (p.matcher(str).find) 1L else 0L

  def Udiv(x: Double): Double = if (x==0.0) 0.0 else 1.0 / x
  def UmulDbl(x: Double, y:Double): Double = x * y
  def UmulLng(x: Long, y:Long): Long = x * y
  def Ulistmax(v1: Long, v2: Long): Long = Math.max(v1, v2)
  def Ulistmax(v1: Double, v2: Double): Double = Math.max(v1, v2)

  def Udate_part(field:String, date:java.util.Date): Long = {
    val c = java.util.Calendar.getInstance; c.setTime(date)
    field.toLowerCase match {
      case "year"  => c.get(java.util.Calendar.YEAR)
      case "month" => c.get(java.util.Calendar.MONTH)
      case "day"   => c.get(java.util.Calendar.DAY_OF_MONTH)
      case p => throw new Exception("Invalid date part: "+p)
    }
  }

  def Udate_part(field:String, date:Long): Long = {
    val c = java.util.Calendar.getInstance; c.setTime(new Date(date))
    field.toLowerCase match {
      case "year"  => c.get(java.util.Calendar.YEAR)
      case "month" => c.get(java.util.Calendar.MONTH)
      case "day"   => c.get(java.util.Calendar.DAY_OF_MONTH)
      case p => throw new Exception("Invalid date part: "+p)
    }
  }

  def Usubstring(s:String,b:Long,e:Long= -1) = if (e== -1) s.substring(b.toInt) else s.substring(b.toInt,e.toInt)

  def Uvec_length(x:Double, y:Double, z:Double):Double = Vector(x,y,z).length
  // def Uvec_dot(x1:Double, y1:Double, z1:Double, x2:Double, y2:Double, z2:Double) = Vector(x1,y1,z1) * Vector(x2,y2,z2)

  private val PI = 3.141592653589793238462643383279502884
  def Uradians(degree:Double) = degree * PI / 180
  def Udegrees(radian:Double) = radian * 180 / PI
  def Upow(x:Double, y:Double) = math.pow(x, y)
  // def Usqrt(x:Double):Double = math.sqrt(x)
  def Ucos(x:Double):Double = math.cos(x)
  def Uvector_angle(x1:Double, y1:Double, z1:Double, x2:Double, y2:Double, z2:Double):Double = {
    val v1 = Vector(x1, y1, z1)
    val v2 = Vector(x2, y2, z2)
    v1.angle(v2)
  }

  def Udihedral_angle(x1:Double, y1:Double, z1:Double,
                      x2:Double, y2:Double, z2:Double,
                      x3:Double, y3:Double, z3:Double,
                      x4:Double, y4:Double, z4:Double) = {
    val p1 = Vector(x1, y1, z1)
    val p2 = Vector(x2, y2, z2)
    val p3 = Vector(x3, y3, z3)
    val p4 = Vector(x4, y4, z4)
    val v1 = p2 - p1
    val v2 = p3 - p2
    val v3 = p4 - p3
    val n1 = v1 * v2
    val n2 = v2 * v3
    math.atan2(v2.length * v1(n2), n1(n2))
  }

  case class Vector(x:Double, y:Double, z:Double) {
    def length:Double = math.sqrt(x*x + y*y + z*z)
    def apply(v:Vector) = x*v.x + y*v.y + z*v.z // dot
    def -(v:Vector) = Vector(x-v.x, y-v.y, z-v.z)
    def *(v:Vector) = Vector(y*v.z-z*v.y, z*v.x-x*v.z, x*v.y-y*v.x)
    def angle(v:Vector) = math.acos ( apply(v) / (length * v.length) )
  }

  def Uhash(n:Long):Long = { // mddb/query2_full.sql
    var v:Long = n * 3935559000370003845L + 2691343689449507681L
    v ^= v >> 21; v^= v << 37; v ^= v >> 4
    v *= 4768777413237032717L
    v ^= v >> 20; v^= v << 41; v ^= v >> 5
    v
  }

  /**
   * Rehashes the contents of this map into a new array with a
   * larger capacity.  This method is called automatically when the
   * number of keys in this map reaches its threshold.
   *
   * If current capacity is MAXIMUM_CAPACITY, this method does not
   * resize the map, but sets threshold to Integer.MAX_VALUE.
   * This has the effect of preventing future calls.
   *
   * @param newCapacity the new capacity, MUST be a power of two;
   *                    must be greater than current capacity unless current
   *                    capacity is MAXIMUM_CAPACITY (in which case value
   *                    is irrelevant).
   */
  // def __resizeHashMap[A<:IEntry:Manifest](oldTable:Array[A], newCapacity: Int): Array[A] = {
  //   val oldCapacity: Int = oldTable.length
    // TODO: make this work properly
    // if (oldCapacity == MAXIMUM_CAPACITY) {
    //   threshold = Integer.MAX_VALUE
    //   return
    // }
    // val newTable: Array[A] = new Array[A](newCapacity)
    // transferHashMap[A](oldTable,newTable)
    // newTable
  // }

  /**
   * Transfers all entries from current table to newTable.
   */
  def __transferHashMap[A<:IEntry:Manifest](src:Array[A], newTable: Array[A]): Array[A] = {
    val newCapacity: Int = newTable.length
    var j: Int = 0
    while (j < src.length) {
      var e: A = src(j)
      if (e != null) {
        src(j) = null.asInstanceOf[A]
        do {
          val next: A = e.nextEntry.asInstanceOf[A]
          val i: Int = __indexForHashMap(e.hashVal, newCapacity)
          e.setNextEntry(newTable(i))
          newTable(i) = e
          e = next
        } while (e != null)
      }
      j += 1; j - 1
    }
    newTable
  }

  /**
   * Returns index for hash code h.
   */
  private def __indexForHashMap(h: Int, length: Int): Int = {
    return h & (length - 1)
  }

  def __deleteEntryHashMap[A<:IEntry:Manifest](map: Array[A], target: A) {

  }
}

trait IEntry { self =>
  def hashVal: Int
  def nextEntry: IEntry
  def setNextEntry(n:IEntry): Unit
}

/* UNUSED LEGACY FUNCTIONS
  def max(v1:Double, v2:Double):Double = if(v1 > v2) v1 else v2
  def min(v1:Double, v2:Double):Double = if(v1 < v2) v1 else v2
  def listmax(v1:Long, v2:Long):Double = max (v1, v2)
  def listmax(v1:Double, v2:Long):Double = max (v1, v2)
  def listmax(v1:Long, v2:Double):Double = max (v1, v2)
  def listmax(v1:Double, v2:Double):Double = max (v1, v2)
  def year_part(date:java.util.Date) = {
    val c = java.util.Calendar.getInstance; c.setTime(date)
    c.get(java.util.Calendar.YEAR)
  }
  def month_part(date:java.util.Date) = {
    val c = java.util.Calendar.getInstance; c.setTime(date)
    c.get(java.util.Calendar.MONTH)
  }
  def day_part(date:java.util.Date):Long = {
    val c = java.util.Calendar.getInstance; c.setTime(date)
    c.get(java.util.Calendar.DAY_OF_MONTH)
  }
  def cast_int(l:Long) = l
  def cast_int(d:Double) = d.toInt
  def cast_int(s:String) = s.toLong
  def cast_float(l:Long) = l.toDouble
  def cast_float(d:Double) = d
  def cast_float(s:String) = s.toDouble
  def cast_string(a:Any) = a.toString
  def cast_string(d:java.util.Date) = new java.text.SimpleDateFormat("yyyy-MM-dd").format(d)
  def cast_date(d:java.util.Date):java.util.Date = d
  def cast_date(s:String):java.util.Date = new java.text.SimpleDateFormat("yyyy-MM-dd").parse(s)
*/