package tpcc.sc

import ddbt.tpcc.itx._
import ddbt.tpcc.loadtest.TpccConstants._
import java.util.Date
import ddbt.lib.store.GenericEntry

import java.sql.Connection
import java.sql.Statement
import java.sql.ResultSet
import ddbt.tpcc.sc.SCExecutor
import ddbt.tpcc.tx._


class InMemorySCTxImpl extends IInMemoryTx {
  var exec: SCExecutor = null

  override def setSharedData(db: AnyRef) = {
    exec = db.asInstanceOf[SCExecutor]
    this
  }
}

class NewOrderSCImpl extends InMemorySCTxImpl with INewOrderInMem {
  override def newOrderTx(datetime: Date, t_num: Int, w_id: Int, d_id: Int, c_id: Int, o_ol_count: Int, o_all_local: Int, itemid: Array[Int], supware: Array[Int], quantity: Array[Int], price: Array[Double], iname: Array[String], stock: Array[Int], bg: Array[String], amt: Array[Double]): Int = {
    exec.newOrderTxInst(SHOW_OUTPUT, datetime, t_num, w_id, d_id, c_id, o_ol_count, o_all_local, itemid, supware, quantity, price, iname, stock, bg, amt)
  }
}

class DeliverySCImpl extends InMemorySCTxImpl with IDeliveryInMem {
  override def deliveryTx(datetime: Date, w_id: Int, o_carrier_id: Int): Int = {
    exec.deliveryTxInst(SHOW_OUTPUT, datetime, w_id, o_carrier_id)
  }
}

class OrderStatusSCImpl extends InMemorySCTxImpl with IOrderStatusInMem {
  override def orderStatusTx(datetime: Date, t_num: Int, w_id: Int, d_id: Int, c_by_name: Int, c_id: Int, c_last: String): Int = {
    exec.orderStatusTxInst(SHOW_OUTPUT, datetime, t_num, w_id, d_id, c_by_name, c_id, c_last)
  }
}

class PaymentSCImpl extends InMemorySCTxImpl with IPaymentInMem {
  override def paymentTx(datetime: Date, t_num: Int, w_id: Int, d_id: Int, c_by_name: Int, c_w_id: Int, c_d_id: Int, c_id: Int, c_last: String, h_amount: Double): Int = {
    exec.paymentTxInst(SHOW_OUTPUT, datetime, t_num, w_id, d_id, c_by_name, c_w_id, c_d_id, c_id, c_last, h_amount)
  }
}

class StockLevelSCImpl extends InMemorySCTxImpl with IStockLevelInMem {
  override def stockLevelTx(t_num: Int, w_id: Int, d_id: Int, threshold: Int): Int = {
    exec.stockLevelTxInst(SHOW_OUTPUT, null, t_num, w_id, d_id, threshold)
  }
}

object SCDataLoader {
  def loadDataIntoMaps(exec: SCExecutor, driverClassName: String, jdbcUrl: String, db_user: String, db_password: String) = {
    val conn = ddbt.tpcc.loadtest.DatabaseConnector.connectToDB(driverClassName, jdbcUrl, db_user, db_password)
    var stmt: Statement = null
    var rs: ResultSet = null
    try {
      stmt = conn.createStatement();

      rs = stmt.executeQuery(TpccSelectQueries.ALL_WAREHOUSES);
      while (rs.next()) {
        exec.x3.insert(GenericEntry("SteNewSEntry",
          rs.getInt("w_id"),
          rs.getString("w_name"),
          rs.getString("w_street_1"),
          rs.getString("w_street_2"),
          rs.getString("w_city"),
          rs.getString("w_state"),
          rs.getString("w_zip"),
          rs.getDouble("w_tax"),
          rs.getDouble("w_ytd")
        ))
      }
      rs.close
      java.lang.System.err.println("Warehouse data loaded")
      rs = stmt.executeQuery(TpccSelectQueries.ALL_CUSTOMERS);
      while (rs.next()) {
        exec.x8.insert(GenericEntry("SteNewSEntry",
          rs.getInt("c_id"),
          rs.getInt("c_d_id"),
          rs.getInt("c_w_id"),
          rs.getString("c_first"),
          rs.getString("c_middle"),
          rs.getString("c_last"),
          rs.getString("c_street_1"),
          rs.getString("c_street_2"),
          rs.getString("c_city"),
          rs.getString("c_state"),
          rs.getString("c_zip"),
          rs.getString("c_phone"),
          rs.getTimestamp("c_since"),
          rs.getString("c_credit"),
          rs.getDouble("c_credit_lim"),
          rs.getDouble("c_discount"),
          rs.getDouble("c_balance"),
          rs.getDouble("c_ytd_payment"),
          rs.getInt("c_payment_cnt"),
          rs.getInt("c_delivery_cnt"),
          rs.getString("c_data")
        ))
      }
      rs.close
      java.lang.System.err.println("Customer data loaded")

      rs = stmt.executeQuery(TpccSelectQueries.ALL_DISTRICTS);
      while (rs.next()) {
        exec.x6.insert(GenericEntry("SteNewSEntry",
          rs.getInt("d_id"),
          rs.getInt("d_w_id"),
          rs.getString("d_name"),
          rs.getString("d_street_1"),
          rs.getString("d_street_2"),
          rs.getString("d_city"),
          rs.getString("d_state"),
          rs.getString("d_zip"),
          rs.getDouble("d_tax"),
          rs.getDouble("d_ytd"),
          rs.getInt("d_next_o_id")
        ))
      }
      rs.close
      java.lang.System.err.println("District data loaded")
      rs = stmt.executeQuery(TpccSelectQueries.ALL_HISTORIES);
      while (rs.next()) {
        exec.x2.insert(GenericEntry("SteNewSEntry",
          rs.getInt("h_c_id"),
          rs.getInt("h_c_d_id"),
          rs.getInt("h_c_w_id"),
          rs.getInt("h_d_id"),
          rs.getInt("h_w_id"),
          rs.getTimestamp("h_date"),
          rs.getDouble("h_amount"),
          rs.getString("h_data")
        ))
      }
      rs.close
      java.lang.System.err.println("History data loaded")
      rs = stmt.executeQuery(TpccSelectQueries.ALL_ITEMS);
      while (rs.next()) {
        exec.x4.insert(GenericEntry("SteNewSEntry",
          rs.getInt("i_id"),
          rs.getInt("i_im_id"),
          rs.getString("i_name"),
          rs.getDouble("i_price"),
          rs.getString("i_data")
        ))
      }
      rs.close
      java.lang.System.err.println("Item data loaded")
      rs = stmt.executeQuery(TpccSelectQueries.ALL_NEW_ORDERS);
      while (rs.next()) {
        exec.x1.insert(GenericEntry("SteNewSEntry",
          rs.getInt("no_o_id"),
          rs.getInt("no_d_id"),
          rs.getInt("no_w_id")
        ))
      }
      rs.close
      java.lang.System.err.println("NewOrder data loaded")
      rs = stmt.executeQuery(TpccSelectQueries.ALL_ORDER_LINES);
      while (rs.next()) {
        exec.x7.insert(GenericEntry("SteNewSEntry",
          rs.getInt("ol_o_id"),
          rs.getInt("ol_d_id"),
          rs.getInt("ol_w_id"),
          rs.getInt("ol_number"),
          rs.getInt("ol_i_id"),
          rs.getInt("ol_supply_w_id"),
          rs.getTimestamp("ol_delivery_d"),
          rs.getInt("ol_quantity"),
          rs.getDouble("ol_amount"),
          rs.getString("ol_dist_info")
        ))
      }
      rs.close
      java.lang.System.err.println("OrderLine data loaded")
      rs = stmt.executeQuery(TpccSelectQueries.ALL_ORDERS);
      while (rs.next()) {
        val o_carrier_id = rs.getInt("o_carrier_id")
        val o_carrier_id_wasNull = rs.wasNull
        val o_all_local = (rs.getInt("o_all_local") > 0)
        exec.x5.insert(GenericEntry("SteNewSEntry",
          rs.getInt("o_id"),
          rs.getInt("o_d_id"),
          rs.getInt("o_w_id"),
          rs.getInt("o_c_id"),
          rs.getTimestamp("o_entry_d"),
          if (o_carrier_id_wasNull) (-1) else rs.getInt("o_carrier_id"),
          rs.getInt("o_ol_cnt"),
          o_all_local
        ))
      }
      rs.close
      java.lang.System.err.println("Order data loaded")
      rs = stmt.executeQuery(TpccSelectQueries.ALL_STOCKS);
      while (rs.next()) {
        exec.x9.insert(GenericEntry("SteNewSEntry",
          rs.getInt("s_i_id"),
          rs.getInt("s_w_id"),
          rs.getInt("s_quantity"),
          rs.getString("s_dist_01"),
          rs.getString("s_dist_02"),
          rs.getString("s_dist_03"),
          rs.getString("s_dist_04"),
          rs.getString("s_dist_05"),
          rs.getString("s_dist_06"),
          rs.getString("s_dist_07"),
          rs.getString("s_dist_08"),
          rs.getString("s_dist_09"),
          rs.getString("s_dist_10"),
          rs.getInt("s_ytd"),
          rs.getInt("s_order_cnt"),
          rs.getInt("s_remote_cnt"),
          rs.getString("s_data")
        ))
      }
      rs.close
      java.lang.System.err.println("Stock data loaded")
      // println("All Tables loaded...")
      // println("Warehouse => " + warehouseTbl)
    } finally {
      if (stmt != null) {
        stmt.close();
      }
    }
  }

  def getAllMapsInfoStr(exec: SCExecutor): String = {
    new StringBuilder("\nTables Info:\nnewOrderTbl => ").append(exec.x1.getInfoStr).append("\n")
      .append("historyTbl => ").append(exec.x2.getInfoStr).append("\n")
      .append("warehouseTbl => ").append(exec.x3.getInfoStr).append("\n")
      .append("itemPartialTbl => ").append(exec.x4.getInfoStr).append("\n")
      .append("orderTbl => ").append(exec.x5.getInfoStr).append("\n")
      .append("districtTbl => ").append(exec.x6.getInfoStr).append("\n")
      .append("orderLineTbl => ").append(exec.x7.getInfoStr).append("\n")
      .append("customerTbl => ").append(exec.x8.getInfoStr).append("\n")
      .append("stockTbl => ").append(exec.x9.getInfoStr).toString
  }

  def moveDataToTpccTable(exec: SCExecutor): TpccTable = {
    val res = new TpccTable
    exec.x1.foreach { e => res.onInsert_NewOrder(e.get[Int](1), e.get[Int](2), e.get[Int](3)) }
    exec.x2.foreach { e => res.onInsert_HistoryTbl(e.get[Int](1), e.get[Int](2), e.get[Int](3), e.get[Int](4), e.get[Int](5), e.get[Date](6), e.get[Double](7), e.get[String](8)) }
    exec.x3.foreach { e => res.onInsert_Warehouse(e.get[Int](1), e.get[String](2), e.get[String](3), e.get[String](4), e.get[String](5), e.get[String](6), e.get[String](7), e.get[Double](8), e.get[Double](9)) }
    exec.x4.foreach { e => res.onInsert_Item(e.get[Int](1), e.get[Int](2), e.get[String](3), e.get[Double](4), e.get[String](5)) }
    exec.x5.foreach { e => res.onInsert_Order(e.get[Int](1), e.get[Int](2), e.get[Int](3), e.get[Int](4), e.get[Date](5), if (e.get[Int](6) == -1) None else Some(e.get[Int](6)), e.get[Int](7), e.get[Boolean](8) )}
    exec.x6.foreach { e => res.onInsert_District(e.get[Int](1), e.get[Int](2), e.get[String](3), e.get[String](4), e.get[String](5), e.get[String](6), e.get[String](7), e.get[String](8), e.get[Double](9), e.get[Double](10), e.get[Int](11)) }
    exec.x7.foreach { e => res.onInsertOrderLine(e.get[Int](1), e.get[Int](2), e.get[Int](3), e.get[Int](4), e.get[Int](5), e.get[Int](6), if (e.get[Date](7) == null) None else Some(e.get[Date](7)), e.get[Int](8), e.get[Double](9), e.get[String](10)) }
    exec.x8.foreach { e => res.onInsertCustomer(e.get[Int](1), e.get[Int](2), e.get[Int](3), e.get[String](4), e.get[String](5), e.get[String](6), e.get[String](7), e.get[String](8), e.get[String](9), e.get[String](10), e.get[String](11), e.get[String](12), e.get[Date](13), e.get[String](14), e.get[Double](15), e.get[Double](16), e.get[Double](17), e.get[Double](18), e.get[Int](19), e.get[Int](20), e.get[String](21)) }
    exec.x9.foreach { e => res.onInsertStock(e.get[Int](1), e.get[Int](2), e.get[Int](3), e.get[String](4), e.get[String](5), e.get[String](6), e.get[String](7), e.get[String](8), e.get[String](9), e.get[String](10), e.get[String](11), e.get[String](12), e.get[String](13), e.get[Int](14), e.get[Int](15), e.get[Int](16), e.get[String](17)) }
    res
  }
}

// class SCTables extends TpccTable {
//   def loadDataIntoMaps(driverClassName:String, jdbcUrl:String, db_user:String, db_password:String) = {
//   }
//   def getAllMapsInfoStr:String = {

//   }
// }