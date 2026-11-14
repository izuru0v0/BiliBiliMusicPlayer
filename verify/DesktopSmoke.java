import javax.swing.*;
import java.awt.*;
public class DesktopSmoke {
 public static void main(String[] args) throws Exception {
  System.setProperty("bilimusic.data",args[0]);
  SwingUtilities.invokeAndWait(()->{
   dev.bilimusic.desktop.DesktopApp app=new dev.bilimusic.desktop.DesktopApp(); app.show();
   JFrame target=null; for(Frame f:Frame.getFrames())if(f instanceof JFrame && f.isVisible())target=(JFrame)f;
   if(target==null || target.getWidth()<1000)throw new AssertionError("Window did not open");
   if(!target.getTitle().equals("B站音乐播放器"))throw new AssertionError("Incorrect product name"); int buttons=count(target.getContentPane()); if(buttons<12)throw new AssertionError("Missing controls");
   try { java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(target.getWidth(),target.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB); target.paint(image.getGraphics()); javax.imageio.ImageIO.write(image,"png",new java.io.File(args[1])); } catch(Exception e) { throw new RuntimeException(e); } target.dispose();System.out.println("PASS: desktop window opens, controls present, clean disposal; buttons="+buttons);
  });
 }
 static int count(Container root){int n=0;for(Component c:root.getComponents()){if(c instanceof JButton)n++;if(c instanceof Container)n+=count((Container)c);}return n;}
}


