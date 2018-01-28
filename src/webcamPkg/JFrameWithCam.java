/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package webcamPkg;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import javax.swing.JOptionPane;
import javax.swing.text.DefaultCaret;
import javax.swing.JLayeredPane;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import java.security.*;
import java.sql.*;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamPanel;
import com.github.sarxos.webcam.WebcamResolution;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Timer;

/**
 *
 * @author guillermo.fernandez
 */
public class JFrameWithCam extends javax.swing.JFrame implements Runnable, ThreadFactory {

    private static final long serialVersionUID = 6441489157408381878L;

    private Executor executor = Executors.newSingleThreadExecutor(this);

    private Webcam webcam = null;
    private WebcamPanel panel = null;
//    private JLayeredPane layeredPane = null;

    public volatile boolean isPaused = false;
    public volatile boolean isStopped = false;

    private String t = "Ticket Scanner";

    private void setThisTitle(String targ) {
        this.setTitle(t + " [" + targ + "]");
    }

    public JFrameWithCam() {
        super();
        initComponents();
        setThisTitle("Capturing");
        DefaultCaret caret = (DefaultCaret) ta1.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        FlowLayout layout = new FlowLayout();
        layout.setAlignment(FlowLayout.LEFT);
        setLayout(layout);
//        Dimension size = WebcamResolution.QQVGA.getSize(); //176x144
//        Dimension size = WebcamResolution.QVGA.getSize(); //320x240
        Dimension size = WebcamResolution.VGA.getSize(); //640x480
        webcam = Webcam.getWebcams().get(0);
        webcam.setViewSize(size);

        panel = new WebcamPanel(webcam);
        panel.setFPSDisplayed(true);
        panel.setDisplayDebugInfo(false);
//        panel.setDisplayDebugInfo(true);
        panel.setImageSizeDisplayed(true);
        panel.setAntialiasingEnabled(false);
        getContentPane().add(panel);
        nameLabel.setText("");
        if (webcam.isOpen()) {
            pauseB.setText("Pause");
        }
        pack();
        setVisible(true);
        executor.execute(this);
    }

    public static String md5(String input) throws NoSuchAlgorithmException {
        String result = input;
        if (input != null) {
            MessageDigest md = MessageDigest.getInstance("MD5"); //or "SHA-1"
            md.update(input.getBytes());
            BigInteger hash = new BigInteger(1, md.digest());
            result = hash.toString(16);
            while (result.length() < 32) { //40 for SHA-1
                result = "0" + result;
            }
        }
        return result;
    }

    //null on error, conn on success
    public static Connection dbcn() {
        try {
            Class.forName("com.mysql.jdbc.Driver");
            String url = "jdbc:mysql://10.200.38.60:3306/ayyqr";
            String username = "root";
            String password = "qwe123";
            Class.forName("com.mysql.jdbc.Driver").newInstance();
            Connection conn = DriverManager.getConnection(url, username, password);
            if (conn == null) {
            } else {
                return conn;
            }
        } catch (ClassNotFoundException | SQLException | InstantiationException | IllegalAccessException e) {
            System.out.println(e);
        }
        return null;
    }

    //-4 on error, -1 on false, fname+lname on success
    static String ticketOwner(Connection conn, String code_ticket) {
        try {
            Statement sql = conn.createStatement();
            ResultSet data = sql.executeQuery("SELECT fname, lname FROM users "
                    + "INNER JOIN purchases ON purchases.owner_id_=users.id "
                    + "INNER JOIN tickets ON tickets.purchase_id_=purchases.id "
                    + "WHERE code='" + code_ticket + "'");
            if (data.next()) {
                String fname = data.getString(1);
                String lname = data.getString(2);
                return fname + " " + lname;
            } else {
                System.out.println("No data");
                return "-1";
            }
        } catch (SQLException | NumberFormatException e) {
            System.out.println(e);
            return "-4";
        }
    }

    //-5 on error, is_used on success
    static int ticketStatus(Connection conn, String code_ticket) {
        int resultVar;
        try {
            Statement sql = conn.createStatement();
            ResultSet data = sql.executeQuery("SELECT is_used FROM tickets WHERE code='" + code_ticket + "'");
            if (data.next()) {
                resultVar = Integer.valueOf(data.getString(1));
                if (resultVar == 0) {
                    System.out.println("Ticket not used yet.");
                } else {
                    System.out.println("Ticket used already.");
                }
                return resultVar;
            } else {
                System.out.println("No data");
                resultVar = -1;
            }
        } catch (SQLException | NumberFormatException e) {
            System.out.println(e);
            resultVar = -5;
        }
        return resultVar;
    }

    //-6 on error, 0 success
    static int usedTicket(Connection conn, String code_ticket) {
        int updateCount;
        try {
            Statement sql = conn.createStatement();
            updateCount = sql.executeUpdate("UPDATE tickets SET is_used=1 WHERE code='" + code_ticket + "'");
            System.out.println("Ticket usado: " + code_ticket);
        } catch (Exception e) {
            System.out.println(e);
            updateCount = -6;
        }
        return updateCount;
    }
    
    private String lastCode = "";
        
    @Override
    @SuppressWarnings({"SleepWhileInLoop", "CallToPrintStackTrace"})
    public void run() {

        Timer clearLabel = new Timer(10000, (ActionEvent e) -> {
            welcomeLabel.setText("AyyQR Scanner");
            nameLabel.setText("");
        });
        clearLabel.setRepeats(false);
        
        Timer clearLastCode = new Timer(30000, (ActionEvent e) -> {
            lastCode = "www";
        });
        clearLastCode.setRepeats(false);

        do {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Result result = null;
            BufferedImage image = null;

            if (webcam.isOpen()) {
                if ((image = webcam.getImage()) == null) {
                    continue;
                }
                LuminanceSource source = new BufferedImageLuminanceSource(image);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                try {
                    result = new MultiFormatReader().decode(bitmap);
                } catch (NotFoundException e) {
                    // fall thru, it means there is no QR code in image
                }
            }
            if (result != null) {
                String code = result.getText();
                if (lastCode.equals(code)) {
                    continue;
                }
                Pattern p = Pattern.compile("[a-zA-Z0-9]{32}");
                Matcher m = p.matcher(code);
                if (m.find()) {
                    System.out.println("MD5 match!");
                } else {
                    System.out.println("Not really an MD5 hash huh");
                    result = null;
                    continue;
                }
                System.out.println("Passed the MD5 check, let's see bois");
                lastCode = code;
                clearLastCode.start();
                /*
                    dbcn(), not null
                    ticketStatus(), -5 on error, -1 on null, 1 on false, 0 on true
                    usedTicket(), not -6
                 */
                String publicCode = code.substring(28, 32); // last 4 chars
                int ticketStatusResponse = ticketStatus(dbcn(), code);
                if (ticketStatusResponse == 0) {
                    if (usedTicket(dbcn(), code) != -6) {
                        String successMsg = "[" + timeStamping() + "] Ticket (" + publicCode + ") verificado. Adelante.\r\n";
                        ta1.append(successMsg);
                        welcomeLabel.setText("Bienvenido,");
                        nameLabel.setText(ticketOwner(dbcn(), code));
                        clearLabel.start();
                    }
                } else if (ticketStatusResponse == 1) {
                    ta1.append("[" + timeStamping() + "] El ticket (" + publicCode + ") ya ha sido utilizado.\r\n");
                } else if (ticketStatusResponse == -1) {
                    ta1.append("[" + timeStamping() + "] Ticket no existe.\r\n");
                } else {
                    ta1.append("[" + timeStamping() + "] Syserror.\r\n");
                }
            }
        } while (true);
    }

    public String timeStamping() {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        return timestamp;
    }

    public void pauseCam() {
        ta1.append("[" + timeStamping() + "] SYS: Pausing camera\r\n");
        panel.pause();
        isPaused = true;
        System.out.println("Camera paused");
        setThisTitle("Paused");
        ta1.append("[" + timeStamping() + "] SYS: Camera paused\r\n");
        pauseB.setText("Resume");
    }

    public void resumeCam() {
        ta1.append("[" + timeStamping() + "] SYS: Resuming camera\r\n");
        panel.resume();
        isPaused = false;
        System.out.println("Camera resumed");
        setThisTitle("Capturing");
        ta1.append("[" + timeStamping() + "] SYS: Camera resumed\r\n");
        pauseB.setText("Pause");
    }

    public void stopCam() {
        ta1.append("[" + timeStamping() + "] SYS: Closing camera\r\n");
        panel.stop();
        isStopped = true;
        System.out.println("Camera closed");
        setThisTitle("Stopped");
        ta1.append("[" + timeStamping() + "] SYS: Camera closed\r\n");
        stopB.setText("Play");
    }

    public void startCam() {
        ta1.append("[" + timeStamping() + "] SYS: Starting camera\r\n");
        panel.start();
        isStopped = false;
        System.out.println("Camera started");
        setThisTitle("Capturing");
        ta1.append("[" + timeStamping() + "] SYS: Camera started\r\n");
        stopB.setText("Stop");
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        mainPanel = new javax.swing.JPanel();
        buttonPanel = new javax.swing.JPanel();
        pauseB = new javax.swing.JButton();
        stopB = new javax.swing.JButton();
        welcomeLabel = new javax.swing.JLabel();
        nameLabel = new javax.swing.JLabel();
        jScrollPane1 = new javax.swing.JScrollPane();
        ta1 = new javax.swing.JTextArea();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("AyyQR Scanner");
        setMinimumSize(null);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowDeiconified(java.awt.event.WindowEvent evt) {
                formWindowDeiconified(evt);
            }
            public void windowIconified(java.awt.event.WindowEvent evt) {
                formWindowIconified(evt);
            }
        });

        mainPanel.setPreferredSize(new java.awt.Dimension(260, 509));

        pauseB.setText("Pause");
        pauseB.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                pauseBActionPerformed(evt);
            }
        });

        stopB.setText("Stop");
        stopB.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                stopBActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout buttonPanelLayout = new javax.swing.GroupLayout(buttonPanel);
        buttonPanel.setLayout(buttonPanelLayout);
        buttonPanelLayout.setHorizontalGroup(
            buttonPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(buttonPanelLayout.createSequentialGroup()
                .addComponent(pauseB, javax.swing.GroupLayout.PREFERRED_SIZE, 93, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(stopB, javax.swing.GroupLayout.PREFERRED_SIZE, 63, javax.swing.GroupLayout.PREFERRED_SIZE))
        );
        buttonPanelLayout.setVerticalGroup(
            buttonPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, buttonPanelLayout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addGroup(buttonPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(pauseB)
                    .addComponent(stopB)))
        );

        welcomeLabel.setFont(new java.awt.Font("Tahoma", 0, 24)); // NOI18N
        welcomeLabel.setText("AyyQR Scanner");

        nameLabel.setFont(new java.awt.Font("Tahoma", 0, 24)); // NOI18N
        nameLabel.setToolTipText("");
        nameLabel.setMaximumSize(new java.awt.Dimension(220, 35));
        nameLabel.setMinimumSize(new java.awt.Dimension(20, 35));
        nameLabel.setPreferredSize(new java.awt.Dimension(20, 35));

        jScrollPane1.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createEtchedBorder(), "Registros"));

        ta1.setEditable(false);
        ta1.setColumns(20);
        ta1.setFont(new java.awt.Font("Tahoma", 0, 11)); // NOI18N
        ta1.setLineWrap(true);
        ta1.setRows(5);
        ta1.setWrapStyleWord(true);
        ta1.setFocusable(false);
        jScrollPane1.setViewportView(ta1);

        javax.swing.GroupLayout mainPanelLayout = new javax.swing.GroupLayout(mainPanel);
        mainPanel.setLayout(mainPanelLayout);
        mainPanelLayout.setHorizontalGroup(
            mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(mainPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(nameLabel, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 260, Short.MAX_VALUE)
                    .addComponent(buttonPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(welcomeLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        mainPanelLayout.setVerticalGroup(
            mainPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, mainPanelLayout.createSequentialGroup()
                .addGap(10, 10, 10)
                .addComponent(welcomeLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(nameLabel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 383, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(buttonPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(mainPanel, javax.swing.GroupLayout.PREFERRED_SIZE, 280, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(350, Short.MAX_VALUE))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(mainPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        mainPanel.getAccessibleContext().setAccessibleDescription("");

        setSize(new java.awt.Dimension(648, 547));
        setLocationRelativeTo(null);
    }// </editor-fold>//GEN-END:initComponents

    private void pauseBActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_pauseBActionPerformed
        // TODO add your handling code here:
        if (isPaused && !isStopped) {
            resumeCam();
        } else if (isPaused && isStopped) {
            JOptionPane.showMessageDialog(this,
                    "No puede pausar la cámara si no se encuentra activa", //content
                    "¡Advertencia!", //title
                    JOptionPane.INFORMATION_MESSAGE
            );
        } else {
            pauseCam();
        }
    }//GEN-LAST:event_pauseBActionPerformed

    private void stopBActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_stopBActionPerformed
        // TODO add your handling code here:
        if (isStopped) {
            startCam();
            if (isPaused) {
                resumeCam();
            }
        } else {
            stopCam();
        }
    }//GEN-LAST:event_stopBActionPerformed

    private void formWindowIconified(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowIconified
        // TODO add your handling code here:
        if (!isPaused) {
            panel.pause();
            setThisTitle("Paused");
            System.out.println("Camera paused");
        }
    }//GEN-LAST:event_formWindowIconified

    private void formWindowDeiconified(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowDeiconified
        // TODO add your handling code here:
        if (!isPaused) {
            panel.resume();
            setThisTitle("Capturing");
            System.out.println("Camera resumed");
        }
    }//GEN-LAST:event_formWindowDeiconified

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "qr-scanner");
        t.setDaemon(true);
        return t;
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Windows".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(JFrameWithCam.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(JFrameWithCam.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(JFrameWithCam.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(JFrameWithCam.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new JFrameWithCam().setVisible(true);
            }
        });

    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel buttonPanel;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JPanel mainPanel;
    private javax.swing.JLabel nameLabel;
    private javax.swing.JButton pauseB;
    private javax.swing.JButton stopB;
    private javax.swing.JTextArea ta1;
    private javax.swing.JLabel welcomeLabel;
    // End of variables declaration//GEN-END:variables
}
