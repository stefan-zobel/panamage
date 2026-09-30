package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModuleSetupTest {

    @Test
    void testsRunInsideTheNamedModule() {
        assertEquals("panamage.jxl", getClass().getModule().getName());
    }
}
