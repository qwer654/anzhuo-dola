package com.doulala.android;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Uses a disposable validation package and one unused high slot; it never touches
 * a real account-bearing installation or public Downloads. */
@RunWith(AndroidJUnit4.class)
public final class SpaceResetInstrumentationTest {
 private static final int SLOT=62, NEIGHBOR=61;
 private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 private static void write(File f)throws Exception{f.getParentFile().mkdirs();try(FileOutputStream o=new FileOutputStream(f)){o.write(7);}}
 private void wipeFixture(Context c){SpaceReset.performBlocking(c,SLOT,SpaceReset.OP_DELETE);c.getSharedPreferences("catalog",0).edit().remove("deleted"+SLOT).remove("resetPending"+SLOT).remove("resetOperation"+SLOT).commit();}
 @Test public void clearOnlyErasesChosenProfileAndRetainsCatalog()throws Exception{
  Context c=context();wipeFixture(c);
  c.getSharedPreferences("catalog",0).edit().putBoolean("enabled"+SLOT,true).putString("name"+SLOT,"keep-name").putString("url"+SLOT,"https://example.test/").putBoolean("enabled"+NEIGHBOR,true).putString("name"+NEIGHBOR,"neighbor").commit();
  File own=new File(c.getDataDir(),"app_webview_space"+SLOT+"/state"); File neighbor=new File(c.getDataDir(),"app_webview_space"+NEIGHBOR+"/state");
  write(own);write(neighbor);write(new File(c.getCacheDir(),"webview_space"+SLOT+"/cache"));write(new File(c.getFilesDir(),"video-Space"+SLOT+"/job"));
  c.getSharedPreferences("browser_Space"+SLOT,0).edit().putString("session","test-only").commit();
  assertTrue(SpaceReset.performBlocking(c,SLOT,SpaceReset.OP_CLEAR));
  assertFalse(own.exists());assertFalse(new File(c.getCacheDir(),"webview_space"+SLOT).exists());assertFalse(new File(c.getFilesDir(),"video-Space"+SLOT).exists());
  assertTrue(neighbor.exists());assertEquals("keep-name",c.getSharedPreferences("catalog",0).getString("name"+SLOT,null));assertTrue(c.getSharedPreferences("catalog",0).getBoolean("enabled"+SLOT,false));assertFalse(SpaceReset.isPending(c,SLOT));
 }
 @Test public void pendingMarkerBlocksAdmissionUntilRetryCompletes()throws Exception{
  Context c=context();wipeFixture(c);c.getSharedPreferences("catalog",0).edit().putBoolean("enabled"+SLOT,true).commit();
  try(SpaceReset.BrowserGate held=SpaceReset.acquireBrowserGate(c,SLOT)) {assertFalse(SpaceReset.performBlocking(c,SLOT,SpaceReset.OP_CLEAR));assertTrue(SpaceReset.isPending(c,SLOT));}
  assertTrue(SpaceReset.performBlocking(c,SLOT,SpaceReset.OP_CLEAR));assertFalse(SpaceReset.isPending(c,SLOT));
 }
 @Test public void deletingSlotZeroDoesNotResurrectDefaultProfile(){Context c=context();c.getSharedPreferences("catalog",0).edit().putBoolean("enabled0",true).putString("name0","zero").commit();assertTrue(SpaceReset.performBlocking(c,0,SpaceReset.OP_DELETE));assertFalse(c.getSharedPreferences("catalog",0).getBoolean("enabled0",false));assertTrue(c.getSharedPreferences("catalog",0).getBoolean("deleted0",false));}
 @Test public void deleteFreesSlotWithoutTouchingNeighbor()throws Exception{
  Context c=context();wipeFixture(c);c.getSharedPreferences("catalog",0).edit().putBoolean("enabled"+SLOT,true).putString("name"+SLOT,"remove-me").putBoolean("enabled"+NEIGHBOR,true).putString("name"+NEIGHBOR,"neighbor").commit();
  File own=new File(c.getDataDir(),"app_webview_space"+SLOT+"/state");File neighbor=new File(c.getDataDir(),"app_webview_space"+NEIGHBOR+"/state");write(own);write(neighbor);
  assertTrue(SpaceReset.performBlocking(c,SLOT,SpaceReset.OP_DELETE));
  assertFalse(own.exists());assertTrue(neighbor.exists());assertFalse(c.getSharedPreferences("catalog",0).getBoolean("enabled"+SLOT,false));assertFalse(c.getSharedPreferences("catalog",0).contains("name"+SLOT));assertFalse(SpaceReset.isPending(c,SLOT));
 }
}
