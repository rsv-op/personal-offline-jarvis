package ai.arena.jarvis;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class BuildSanityTest {

    @Test
    public void applicationPackageNameIsCorrect() {
        assertEquals("ai.arena.jarvis", MainActivity.class.getPackage().getName());
    }

    @Test
    public void launcherActivityClassExists() {
        assertNotNull(MainActivity.class);
    }

    @Test
    public void basicAssertionsExecute() {
        assertTrue("JARVIS build sanity check", true);
    }
}
