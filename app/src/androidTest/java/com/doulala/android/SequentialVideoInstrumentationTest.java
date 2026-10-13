package com.doulala.android;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONTokener;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class SequentialVideoInstrumentationTest {
 @Test public void ffmpegExtractsActualBlueLastFrameNotRedFirst() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  File dir=new File(c.getCacheDir(),"ffmpeg-fixture");assertTrue(dir.isDirectory()||dir.mkdirs());
  File video=new File(dir,"two-colors.mp4"),jpg=new File(dir,"last.jpg");
  try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("last-frame.mp4.b64");ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);Files.write(video.toPath(),Base64.decode(out.toByteArray(),Base64.DEFAULT));
  }
  FfmpegLastFrame.extract(c,video,jpg,()->false);Bitmap image=BitmapFactory.decodeFile(jpg.getAbsolutePath());assertNotNull(image);
  int pixel=image.getPixel(image.getWidth()/2,image.getHeight()/2);assertTrue("must retain blue last frame",Color.blue(pixel)>Color.red(pixel)+100);image.recycle();
 }
 @Test public void cancelledExtractionNeverStarts() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  try{FfmpegLastFrame.extract(c,new File(c.getCacheDir(),"unused.mp4"),new File(c.getCacheDir(),"unused.jpg"),()->true);fail();}
  catch(IOException e){assertTrue(e.getMessage().contains("cancelled"));}
 }
 @Test public void importedParamsKeepTypesAndNoDefaultGateway() throws Exception {
  String json="{\"schemaVersion\":1,\"sequenceId\":\"synthetic\",\"gateway\":\"https://example.test\",\"segments\":[{\"model\":\"unchanged\",\"prompt\":\"sample\",\"referenceImages\":[\"https://example.test/asset.jpg\"],\"params\":{\"duration\":30,\"audio\":true,\"nested\":{\"seed\":9}}}]}";
  SequentialVideoActivity.Job j=SequentialVideoActivity.Job.parse(json);
  assertEquals("unchanged",j.segments.get(0).model());assertEquals(30,new JSONTokener(j.segments.get(0).originalParams().get("duration")).nextValue());assertEquals(Boolean.TRUE,new JSONTokener(j.segments.get(0).originalParams().get("audio")).nextValue());
  assertEquals(1,j.segments.get(0).originalRefs().size());
  try{new VideoGateway("http://example.test","");fail();}catch(Exception expected){}
 }
}
