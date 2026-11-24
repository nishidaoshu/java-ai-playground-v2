package org.vaadin.marcus.v1;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
class DatabaseGuard {
    final JdbcTemplate db;DatabaseGuard(JdbcTemplate db){this.db=db;}
    @PostConstruct void verify(){String name=db.queryForObject("select current_database()",String.class);if(!java.util.Set.of("java_ai_v2","java_ai_v2_test").contains(name))throw new IllegalStateException("V2拒绝连接非V2数据库");}
    @org.springframework.context.annotation.Bean
    static org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy guardedMigration(){return flyway->{try(var connection=flyway.getConfiguration().getDataSource().getConnection();var stmt=connection.createStatement();var rows=stmt.executeQuery("select current_database()")){rows.next();if(!java.util.Set.of("java_ai_v2","java_ai_v2_test").contains(rows.getString(1)))throw new IllegalStateException("迁移前拒绝非V2数据库");}catch(java.sql.SQLException e){throw new IllegalStateException("数据库校验失败");}flyway.migrate();};}
}
