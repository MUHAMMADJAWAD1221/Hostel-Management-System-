package com.indushostel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;
import java.util.Map;

@Component
public class ScheduledJobs {
    private static final Logger log=LoggerFactory.getLogger(ScheduledJobs.class);
    private final JdbcTemplate db; private final HostelService hostel; private final MonthlyReportService reports; private final ZoneId zone;
    public ScheduledJobs(JdbcTemplate db,HostelService hostel,MonthlyReportService reports,@Value("${app.hostel-timezone:Asia/Karachi}") String timezone){this.db=db;this.hostel=hostel;this.reports=reports;this.zone=ZoneId.of(timezone);}

    @Scheduled(cron="0 0 0 1 * *",zone="${app.hostel-timezone:Asia/Karachi}")
    public void monthlyFees(){run("monthly-fees:"+YearMonth.now(zone),()->{int n=hostel.generateInvoicesFor(YearMonth.now(zone));log.info("Generated {} student fee invoices for {}",n,YearMonth.now(zone));});}

    @Scheduled(cron="0 30 8 * * *",zone="${app.hostel-timezone:Asia/Karachi}")
    public void billReminders(){run("bill-reminders:"+LocalDate.now(zone),this::updateBillsAndRemind);}

    @Scheduled(cron="0 0 9,12,15 5 * *",zone="${app.hostel-timezone:Asia/Karachi}")
    public void monthlyOwnerReport(){YearMonth previous=YearMonth.now(zone).minusMonths(1);run("monthly-report:"+previous+":"+LocalDate.now(zone)+":"+LocalTime.now(zone).getHour(),()->reports.scheduledPreviousMonth(previous));}

    @Scheduled(cron="0 15 9 * * *",zone="${app.hostel-timezone:Asia/Karachi}")
    public void retryReportDelivery(){run("report-retry:"+LocalDate.now(zone),reports::retryFailed);}

    private void updateBillsAndRemind(){
        LocalDate today=LocalDate.now(zone),horizon=today.plusDays(14);
        db.update("UPDATE bill_payments SET status='OVERDUE' WHERE status<>'PAID' AND due_date<?",today);
        db.update("UPDATE bill_payments SET status='DUE' WHERE status='UPCOMING' AND due_date<=?",today);
        List<Map<String,Object>> schedules=db.queryForList("SELECT id,category,expected_amount,frequency,next_due_date FROM recurring_bills WHERE active=TRUE AND next_due_date<=?",horizon);
        for(Map<String,Object> s:schedules){
            long id=((Number)s.get("id")).longValue();LocalDate due=((java.sql.Date)s.get("next_due_date")).toLocalDate();
            db.update("INSERT IGNORE INTO bill_payments(recurring_bill_id,category,amount,due_date,status,recorded_by) SELECT ?,?,?,?,CASE WHEN ?<CURDATE() THEN 'OVERDUE' WHEN ?<=CURDATE() THEN 'DUE' ELSE 'UPCOMING' END,id FROM users WHERE role='OWNER' ORDER BY id LIMIT 1",id,s.get("category"),s.get("expected_amount"),due,due,due);
            LocalDate next=due;String frequency=(String)s.get("frequency");int count=0;
            while(!next.isAfter(horizon)&&count++<12){next=switch(frequency){case "QUARTERLY"->next.plusMonths(3);case "YEARLY"->next.plusYears(1);default->next.plusMonths(1);};}
            db.update("UPDATE recurring_bills SET next_due_date=? WHERE id=?",next,id);
        }
        for(Map<String,Object> bill:db.queryForList("SELECT id,category,due_date AS dueDate,status FROM bill_payments WHERE status<>'PAID' AND due_date<=?",horizon)){
            long id=((Number)bill.get("id")).longValue();String kind=((java.sql.Date)bill.get("dueDate")).toLocalDate().isBefore(today)?"BILL_OVERDUE":"BILL_UPCOMING";
            String title=kind.equals("BILL_OVERDUE")?"Utility bill overdue":"Upcoming utility bill";
            String message=bill.get("category")+" bill due "+bill.get("dueDate");
            db.update("INSERT INTO notifications(user_id,kind,title,message,related_type,related_id,dedupe_key) SELECT id,?,?,?,'bill',?,CONCAT('bill-reminder-',?) FROM users WHERE role IN ('OWNER','MANAGER') ON DUPLICATE KEY UPDATE id=id",kind,title,message,id,id);
        }
        for(Map<String,Object> invoice:db.queryForList("SELECT i.id,i.student_id AS studentId,i.due_date AS dueDate FROM fee_invoices i WHERE i.fee_amount>i.paid_amount AND (i.due_date<? OR i.due_date=?)",today,today.plusDays(3))){
            long invoiceId=((Number)invoice.get("id")).longValue();Long user=db.queryForObject("SELECT id FROM users WHERE student_id=?",Long.class,invoice.get("studentId"));
            LocalDate due=((java.sql.Date)invoice.get("dueDate")).toLocalDate();String kind=due.isBefore(today)?"FEE_OVERDUE":"FEE_DUE";
            String title=due.isBefore(today)?"Hostel fee overdue":"Hostel fee due soon";
            db.update("INSERT INTO notifications(user_id,kind,title,message,related_type,related_id,dedupe_key) VALUES(?,?,?,?, 'invoice',?,?) ON DUPLICATE KEY UPDATE id=id",user,kind,title,"Your hostel fee is due "+due,invoiceId,"fee-reminder-"+invoiceId);
        }
    }

    private void run(String key,Runnable work){
        try{
            int inserted=db.update("INSERT IGNORE INTO scheduled_runs(job_key,status) VALUES(?,'RUNNING')",key);
            if(inserted==0){
                String status=db.queryForObject("SELECT status FROM scheduled_runs WHERE job_key=?",String.class,key);
                if("RUNNING".equals(status)){
                    log.info("Skipping scheduled job {} because it is already running", key);
                    return;
                }
                db.update("UPDATE scheduled_runs SET status='RUNNING',started_at=CURRENT_TIMESTAMP,finished_at=NULL,details=NULL WHERE job_key=?",key);
            }
            work.run();
            db.update("UPDATE scheduled_runs SET status='SUCCEEDED',finished_at=CURRENT_TIMESTAMP WHERE job_key=?",key);
        }catch(Exception e){String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();if(message.length()>900)message=message.substring(0,900);try{db.update("UPDATE scheduled_runs SET status='FAILED',finished_at=CURRENT_TIMESTAMP,details=? WHERE job_key=?",message,key);}catch(Exception ignored){}log.error("Scheduled task {} failed",key,e);}
    }
}
