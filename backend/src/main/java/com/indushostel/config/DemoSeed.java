package com.indushostel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

@Component
@Profile("dev")
public class DemoSeed implements CommandLineRunner {
    private final JdbcTemplate db; private final PasswordEncoder encoder; private final boolean enabled;
    public DemoSeed(JdbcTemplate db,PasswordEncoder encoder,@Value("${app.seed-demo:false}") boolean enabled){this.db=db;this.encoder=encoder;this.enabled=enabled;}
    @Override public void run(String...args) {
        if(!enabled||db.queryForObject("SELECT COUNT(*) FROM users",Integer.class)>0)return;
        long owner=user("demo-owner@example.test","LocalDemo-Owner!2026","Demo Owner","OWNER",null);
        user("demo-manager@example.test","LocalDemo-Manager!2026","Demo Manager","MANAGER",null);
        long room=insert("INSERT INTO rooms(room_number,section_name,capacity,notes) VALUES('A-01','Ground floor',4,'Fictional development seed room')");
        long student=insert("INSERT INTO students(student_code,full_name,contact,registration_date,check_in_date,monthly_fee,due_day,status,notes) VALUES('DEMO-001','Sample Student','03000000000',CURDATE(),CURDATE(),18000,5,'ACTIVE','Fictional development seed record')");
        user("demo-student@example.test","LocalDemo-Student!2026","Sample Student","STUDENT",student);
        db.update("INSERT INTO student_assignments(student_id,room_id,bed_number,started_at,created_by) VALUES(?,?, '1',CURDATE(),?)",student,room,owner);
        LocalDate period=YearMonth.now().atDay(1);
        db.update("INSERT INTO fee_invoices(student_id,billing_month,fee_amount,due_date) VALUES(?,?,18000,DATE_ADD(?,INTERVAL 4 DAY))",student,period,period);
    }
    private long user(String email,String pass,String name,String role,Long student){return insert("INSERT INTO users(email,password_hash,display_name,role,student_id) VALUES(?,?,?,?,?)",email,encoder.encode(pass),name,role,student);}
    private long insert(String sql,Object...values){KeyHolder key=new GeneratedKeyHolder();db.update(c->{PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s;},key);return Objects.requireNonNull(key.getKey()).longValue();}
}
