package com.picoxr.resfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LaunchScopeTest {
    @Test
    public void nestedLaunchScopesResolveToTheInnermostPackage() {
        try {
            assertNull(ResFix.currentLaunchPackage());
            assertTrue(ResFix.pushLaunchPackage("outer").isPresent());
            ResFix.pushLaunchPackage("inner");
            assertEquals("inner", ResFix.currentLaunchPackage());
        } finally {
            ResFix.popLaunchPackage("inner");
            ResFix.popLaunchPackage("outer");
            ResFix.clearLaunchPackage();
        }
    }

    @Test
    public void outerPackageIsVisibleAfterInnerScopeFinishes() {
        try {
            ResFix.clearLaunchPackage();
            ResFix.pushLaunchPackage("outer");
            ResFix.pushLaunchPackage("inner");
            ResFix.popLaunchPackage("inner");
            assertEquals("outer", ResFix.currentLaunchPackage());
        } finally {
            ResFix.clearLaunchPackage();
        }
    }

    @Test
    public void nullPushLeavesExistingScopeUnchanged() {
        try {
            ResFix.clearLaunchPackage();
            ResFix.pushLaunchPackage("only");
            ResFix.pushLaunchPackage(null);
            assertEquals("only", ResFix.currentLaunchPackage());
        } finally {
            ResFix.clearLaunchPackage();
        }
    }
}
