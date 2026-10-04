package com.creatorcrm.update;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.config.CrmProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PreUpdateBackupTest {

    @Test
    void copiesTheEmbeddedDatabaseAndKeepsTheNewestThree(@TempDir Path home) throws Exception {
        Path data = home.resolve("data");
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:file:" + data.resolve("creator-crm") + ";MODE=PostgreSQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE deals (id INT)");
        CrmProperties props = new CrmProperties(data.toString(), "http://localhost:8080", null, null, null, null, null,
                new CrmProperties.Updates(true, "acme/crm", "http://127.0.0.1:1", "http://127.0.0.1:1", "-", "", ""));
        UpdateService updates = new UpdateService(props, null, jdbc, ds, null,
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class));

        for (int i = 0; i < 4; i++) {
            Path zip = updates.backupDatabase();
            assertThat(zip).isRegularFile().hasParent(home.resolve("backups"));
            assertThat(Files.size(zip)).isPositive();
            zip.toFile().setLastModified(System.currentTimeMillis() - (4 - i) * 60_000L);
            Thread.sleep(1100); // file names carry the second
        }
        try (Stream<Path> files = Files.list(home.resolve("backups"))) {
            assertThat(files.count()).isEqualTo(3);
        }
    }
}
