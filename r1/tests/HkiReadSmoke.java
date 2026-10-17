import dev.soundconnect.r1.HkiProfile;
import java.nio.file.*;
public class HkiReadSmoke {
 public static void main(String[] args) throws Exception {
  HkiProfile p=HkiProfile.parse(Files.readAllBytes(Paths.get(args[0])));
  System.out.println("sampleRate="+p.sampleRate+" taps="+p.taps+" directions="+p.directions.length);
  for(HkiProfile.Direction d:p.directions) {
   double a=0,b=0;int peak=0;
   for(int i=0;i<p.taps;i++){a+=d.filters[0][i]*d.filters[0][i];b+=d.filters[1][i]*d.filters[1][i];if(Math.abs(d.filters[0][i])>Math.abs(d.filters[0][peak]))peak=i;}
   System.out.println("az="+d.azimuth+" polar="+d.polar+" energy="+a+","+b+" peak="+peak);
  }
 }
}
