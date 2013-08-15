package ddbt.lib

import akka.actor.{Actor,ActorRef,ActorSystem,Props,Deploy,Address}
import akka.remote.RemoteScope
import scala.reflect.ClassTag
import java.io.InputStream

trait Helper {
  import Messages._

  // ---------------------------------------------------------------------------
  // Remoting helpers
  def sys(name:String,host:String,port:Int) = {
    val conf = "akka.loglevel=ERROR\nakka.log-dead-letters-during-shutdown=off\n"+ // disable verbose logging
               "akka {\nactor.provider=\"akka.remote.RemoteActorRefProvider\"\nremote.netty {\nhostname=\""+host+"\"\ntcp.port="+port+"\n}\n}\n"
    val system = ActorSystem(name, com.typesafe.config.ConfigFactory.parseString(conf))
    Runtime.getRuntime.addShutdownHook(new Thread{ override def run() = { /*println("Stopping "+host+":"+port);*/ system.shutdown() } });
    /*println("Started "+host+":"+port);*/ system
  }
  def props[A<:Actor](name:String,host:String,port:Int)(implicit cA:ClassTag[A]) = {
    Props(cA.runtimeClass).withDeploy(Deploy(scope = RemoteScope(new Address("akka.tcp",name,host,port))))
  }
  
  // ---------------------------------------------------------------------------
  // Run query actor and collect time+resulting map
  // T is usually Map[K,V]. For multiple maps, it is a tuple (Map[K1,V1],Map[K2,V2],...)
  
  def mux[T](actor:ActorRef,streams:Seq[(InputStream,Adaptor,Split)],parallel:Boolean=false,wait:Int=60000) = {
    val mux = SourceMux(streams.map {case (in,ad,sp) => (in,Decoder((ev:TupleEvent)=>{ actor ! ev },ad,sp))},parallel)
    actor ! SystemInit
    // preload existing tables in the query
    mux.read()
    val timeout = akka.util.Timeout(wait)
    scala.concurrent.Await.result(akka.pattern.ask(actor,EndOfStream)(timeout), timeout.duration).asInstanceOf[(Long,T)]
  }
  
  def run[Q<:akka.actor.Actor,T](streams:Seq[(InputStream,Adaptor,Split)],parallel:Boolean=false)(implicit cq:ClassTag[Q]):(Long,T) = {
    val system = ActorSystem("DDBT")
    val query = system.actorOf(Props[Q],"Query")
    val res = mux[T](query,streams,parallel)
    system.shutdown; res
  }
    
  def runLocal[M<:akka.actor.Actor,W<:akka.actor.Actor,T](port:Int,N:Int,streams:Seq[(InputStream,Adaptor,Split)],parallel:Boolean=false)(implicit cm:ClassTag[M],cw:ClassTag[W]):(Long,T) = {
    val system:ActorSystem = this.sys("MasterSystem","127.0.0.1",port-1)
    val nodes = (0 until N).map { i => sys("NodeSystem"+i,"127.0.0.1",port+i) }
    val wprops = (0 until N).map { i=>props[W]("NodeSystem"+i,"127.0.0.1",port+i) }.toArray
    val master = system.actorOf(Props(cm.runtimeClass,wprops))
    val res = mux[T](master,streams,parallel)
    Thread.sleep(100); nodes.foreach{ _.shutdown }; system.shutdown; Thread.sleep(100); res
  }
  
  def time(ns:Long) = { val ms=ns/1000000; "%d.%03d".format(ms/1000,ms%1000) }
  def bench[T](name:String,count:Int,f:()=>(Long,T)):T = {
    val out = (0 until count).map { x => f() }
    val res = out.map(_._2).toList; assert(res.tail.filter{ x=> x!=res.head }.isEmpty)
    val ts = out.map(_._1).sorted;
    println(name+" : "+time(ts(count/2))+" ["+time(ts(0))+", "+time(ts(count-1))+"] (sec)"); res.head
  }

  // ---------------------------------------------------------------------------
  // Unit testing helpers
  def diff[K,V](map1:Map[K,V],map2:Map[K,V]) {
    val m1 = map1.filter{ case (k,v) => map2.get(k) match { case Some(v2) => v2!=v case None => true } }
    val m2 = map2.filter{ case (k,v) => map1.get(k) match { case Some(v2) => v2!=v case None => true } }
    if (m1.size>0||m2.size>0) {
      //println("---- Result -------------------------"); println(K3Helper.toStr(m1))
      //println("---- Reference ----------------------"); println(K3Helper.toStr(m2))
      //assert(m1==m2)

      val ks=m1.keys++m2.keys
      var err=false
      ks.foreach { k=>
        val v1=m1.getOrElse(k,null);
        val v2=m2.getOrElse(k,null);
        if (v1==null) { println("Missing key: "+k+" -> "+v2); err=true; }
        else if (v2==null) { println("Extra key: "+k+" -> "+v1); err=true; }
        else try { diff(v1,v2) } catch { case _:Throwable => println("Bad value: "+k+" -> "+v1+" (expected "+v2+")"); err=true }
      }
      if (err) { println("--------------------"); assert(m1==m2) }
    }
  }
  
  val precision = 10 // significative numbers
  private val diff_p = Math.pow(0.1,precision)
  def diff[V](v1:V,v2:V) = if (v1!=v2) (v1,v2) match {
    case (d1:Double,d2:Double) => assert(Math.abs(2*(d1-d2)/(d1+d2))<diff_p)
    case _ => assert(false)
  }
  
  def loadCSV[K,V](kv:List[Any]=>(K,V),file:String,fmt:String,sep:String=","):Map[K,V] = {
    val m = new java.util.HashMap[K,V]()
    def f(e:TupleEvent) = { val (k,v)=kv(e.data); m.put(k,v) }
    val d = Decoder(f,new Adaptor.CSV("REF",fmt,sep),Split())
    val s = SourceMux(Seq((new java.io.FileInputStream(file),d)))
    s.read; scala.collection.JavaConversions.mapAsScalaMap(m).toMap
  }

  // ---------------------------------------------------------------------------
  // Stream definitions
  def streamsFinance(s:String="") = {
    val file = new java.io.FileInputStream("resources/data/finance"+(if (s!="") "-"+s else "")+".csv")
    Seq((file,Adaptor("orderbook",Nil),Split()))
  }
  private def s(n:String,s:String=null) = {
    val fmt = if (s!=null) s else n.toLowerCase match {
      case "orders" => "int,int,string,float,date,string,string,int,string"
      case "customer" => "int,string,string,int,string,float,string,string"
      case "supplier" => "int,string,string,int,string,float,string"
      case "lineitem" => "int,int,int,int,float,float,float,float,string,string,date,date,date,string,string,string"
    }
    (new java.io.FileInputStream("resources/data/tpch/"+n+".csv"),new Adaptor.CSV(n.toUpperCase,fmt,"\\|"),Split())
  }
  def streamsTPCH1() = Seq(s("lineitem"))
  def streamsTPCH13() = Seq(
        s("orders","int,int,string,float,date,string,string,int,string"),
        s("customer","int,string,string,int,string,float,string,string"))
  def streamsTPCH15() = Seq(
        s("lineitem","int,int,int,int,float,float,float,float,string,string,date,date,date,string,string,string"),
        s("supplier","int,string,string,int,string,float,string"))
  def streamsTPCH18() = Seq(
        s("lineitem","int,int,int,int,float,float,float,float,string,string,date,date,date,string,string,string"),
        s("orders","int,int,string,float,date,string,string,int,string"),
        s("customer","int,string,string,int,string,float,string,string"))
}
