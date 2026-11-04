package dev.dov.internettools;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
public class RuntimeBuildStampTest {
    @org.junit.Test public void nativeReceiptIsAValidatedPackageHash()throws Exception{
        java.io.File f=java.io.File.createTempFile("native-receipt",".txt");
        try{String hash=new String(new char[64]).replace('\0','a');assertTrue(RuntimeBuildStamp.recordInstallReceipt(f,hash));assertTrue(RuntimeBuildStamp.matches(f,hash));
            try{RuntimeBuildStamp.recordInstallReceipt(f,"not a hash");fail("Invalid identity must not be cached");}catch(java.io.IOException expected){}
            assertTrue(RuntimeBuildStamp.matches(f,hash));
        }finally{f.delete();}
    }
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 @Test public void displayVersionAndOldRawVersionCannotConfirmTheNewBuild()throws Exception{
  File file=tmp.newFile();String hash=new String(new char[64]).replace('\0','a');
  assertFalse(RuntimeBuildStamp.matches(file,hash));
  assertFalse(RuntimeBuildStamp.confirm(file,"1.75.0.2","1.75.0",hash));
  assertFalse(RuntimeBuildStamp.confirm(file,"1.75.0.2","1.75.0.1",hash));assertFalse(RuntimeBuildStamp.matches(file,hash));
  assertTrue(RuntimeBuildStamp.confirm(file,"1.75.0.2","1.75.0.2",hash));assertTrue(RuntimeBuildStamp.matches(file,hash));
  assertFalse(RuntimeBuildStamp.matches(file,new String(new char[64]).replace('\0','b')));
 }
}
