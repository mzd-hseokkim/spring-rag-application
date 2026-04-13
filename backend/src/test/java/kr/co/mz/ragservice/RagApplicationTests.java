package kr.co.mz.ragservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class RagApplicationTests {

    @Test
    void contextLoads(org.springframework.context.ApplicationContext context) {
        assertNotNull(context);
    }
}
