import dev.soundconnect.r1.*;
import java.io.*;
import java.nio.file.*;
public class ExportBrir {
 public static void main(String[] a) throws Exception {
  HkiProfile p=HkiProfile.parse(Files.readAllBytes(Paths.get(a[0])));
  try(OutputStream out=new FileOutputStream(a[1])) {MpeghBrir.write(p,out);}
 }
}
