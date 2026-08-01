package com.alejandro.mtostock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "spring.data.jpa.auditing.enabled=false"
})
class MtoStockApplicationTests {

    @Test
    void contextLoads() {
    }

}
