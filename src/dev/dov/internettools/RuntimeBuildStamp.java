package dev.dov.internettools;
import java.io.*;
import java.nio.charset.StandardCharsets;
final class RuntimeBuildStamp {
    static boolean matches(File file,String hash){
        if(file.length()!=64)return false;
        try(FileInputStream in=new FileInputStream(file)){byte[] data=new byte[64];int offset=0,n;while(offset<64&&(n=in.read(data,offset,64-offset))>0)offset+=n;return offset==64&&hash.equals(new String(data,StandardCharsets.US_ASCII));}catch(IOException e){return false;}
    }
    static boolean confirm(File file,String expectedRawVersion,String reportedRawVersion,String hash)throws IOException{
        if(!expectedRawVersion.equals(reportedRawVersion))return false;
        return recordInstallReceipt(file,hash);
    }
    // A native install receipt caches package identity only, never filtering readiness.
    static boolean recordInstallReceipt(File file,String hash)throws IOException{
        if(!hash.matches("[0-9a-f]{64}"))throw new IOException("invalid_build_hash");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(hash.getBytes(StandardCharsets.US_ASCII));}return true;
    }
}
