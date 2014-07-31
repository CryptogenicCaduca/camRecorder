package com.server;

import com.model.Cam;
import com.model.Model;
import com.model.Settings;
import com.net.rtp.H264RTP;
import com.net.rtsp.Reply;
import com.net.rtsp.Rtsp;
import com.net.rtsp.SDP;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by calc on 22.07.14.
 *
 */
public class Server {
    private static Logger log = Logger.getLogger(Server.class.getName());

    List<Thread> threads = new ArrayList<Thread>();
    private boolean stop = true;

    private class CamHolder implements Runnable{
        private final Cam cam;

        private CamHolder(Cam cam) {
            this.cam = cam;
        }

        @Override
        public void run() {
            String proto = cam.getUrl().getScheme();
            if(proto.equals("rtsp")){
                rtsp(cam);
            } else if(proto.equals("http")){
                http(cam);
            }
            else{
                throw new NotImplementedException("protocol " + proto + " not implemented");
            }
        }
    }

    public void start() throws NotImplementedException {
        log.info("start server");
        log.info(String.format("settings \n ffmpeg: %s\n vlc: %s\n seconds: %d\n",
                Settings.getInstance().getFfmpegPath(),
                Settings.getInstance().getVlcPath(),
                Settings.getInstance().getSeconds()
                ));

        threads.clear();
        if(!isStop()) {
            log.warning("server already started");
            return;
        }

        Cam cam = new Cam();

        try {
            List<Cam> list = Model.selectAll(cam);

            if(list.size() == 0) return;

            stop = false;

            for(Cam c : list){
                threads.add(new Thread(new CamHolder(c)));
            }

            for(Thread t : threads){
                t.start();
            }

        } catch (SQLException e) {
            log.log(Level.SEVERE, ExceptionUtils.getStackTrace(e));
            //e.printStackTrace();
        }
    }

    public void stop(){
        log.info("stop");
        stop = true;

        for(Thread t : threads){
            try {
                t.join();
            } catch (InterruptedException e) {
                log.log(Level.SEVERE, ExceptionUtils.getStackTrace(e));
                //e.printStackTrace();
            }
        }
    }

    private SDP.FMTP getFmtp(SDP sdp){
        SDP.FMTP fmtp = null;
        if(sdp.getMediaByType(SDP.MediaType.video).get(0).getAttribute(SDP.AttributeName.fmtp) != null){
            fmtp = sdp.new FMTP(sdp.getMediaByType(SDP.MediaType.video).get(0).getAttribute(SDP.AttributeName.fmtp).get(0));
        }
        return fmtp;
    }

    private void http(Cam cam){
        try {
            ArchiveRotator rotator = new ArchiveRotator(cam);
            rotator.rotate();
            URL url = new URL(cam.getUrl().toString());
            final HTTPReceiver HTTPReceiver = new HTTPReceiver(url, rotator);

            HTTPReceiver.play();

            while (!stop){
                int wait = Settings.getInstance().getSeconds();
                while(wait > 0 && !stop){
                    Thread.sleep(1000);
                    wait--;
                }

                if(!stop){
                    rotator.rotate();
                }
                else
                    break;
            }

            HTTPReceiver.stop();
            rotator.close();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            stop = true;
        }
    }

    private void rtsp(Cam cam){
        final Rtsp rtsp = new Rtsp();

        try {
            //rtsp.setDebug(Settings.getInstance().isDebug());
            rtsp.connect(cam.getUrl());

            rtsp.options();
            SDP sdp = rtsp.describe();

            //добавить проверки
            String video = sdp.getMediaByType(SDP.MediaType.video).get(0).getAttribute(SDP.AttributeName.control).get(0);
            if(log.isLoggable(Level.FINE)) log.fine("video:" + video);
            String audio = sdp.getMediaByType(SDP.MediaType.audio).get(0).getAttribute(SDP.AttributeName.control).get(0);
            if(log.isLoggable(Level.FINE)) log.fine("audio:" + audio);

            SDP.FMTP fmtp = getFmtp(sdp);

            int[] ports = {49501, 49502, 49503, 49504};
            rtsp.setMap(ports);
            boolean interleaved = true;

            //rtsp.setup(video, 0, 1, true, "");
            rtsp.setup(video, interleaved);
            Reply reply = rtsp.getLastReply();
            String session = reply.getSession();
            if(reply.getCode() == 403){
                log.warning("Non interleaved mode not supported");
                interleaved = !interleaved;
                rtsp.setup(video, interleaved);
            }

            if(log.isLoggable(Level.FINE)) log.fine("session: " + session);
            rtsp.setup(audio, interleaved);

            ByteArrayOutputStream fmtpBuffer = new ByteArrayOutputStream();
            if(fmtp != null){
                fmtpBuffer.write(H264RTP.NON_IDR_PICTURE);
                fmtpBuffer.write(fmtp.getSps());
                fmtpBuffer.write(H264RTP.NON_IDR_PICTURE);
                fmtpBuffer.write(fmtp.getPps());
            }

            OutputStream[] outs = new OutputStream[4];
            ArchiveRotator rotator = new ArchiveRotator(cam);
            if(fmtp != null) rotator.rotate(fmtpBuffer.toByteArray());
            else rotator.rotate();
            //save only video
            outs[0] = rotator;

            rtsp.play(outs);

            while (!stop){
                int wait = Settings.getInstance().getSeconds();
                while(wait > 0 && !stop){
                    Thread.sleep(1000);
                    wait--;
                }

                if(!stop){
                    if(fmtp != null) rotator.rotate(fmtpBuffer.toByteArray());
                    else rotator.rotate();
                }
            }
            try {
                rtsp.stop();
            } catch (Exception e) {
                log.log(Level.SEVERE, ExceptionUtils.getStackTrace(e));
            } finally {
                rotator.close();
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, ExceptionUtils.getStackTrace(e));
        } catch (InterruptedException e) {
            log.log(Level.SEVERE, ExceptionUtils.getStackTrace(e));
        } catch (SQLException e) {
            log.log(Level.SEVERE, ExceptionUtils.getStackTrace(e));
        } finally {
            stop = true;
        }
    }

    public boolean isStop() {
        return stop;
    }
}
