package de.mhus.app.kpush;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import de.mhus.lib.core.M;
import de.mhus.lib.core.MDate;
import de.mhus.lib.core.MFile;
import de.mhus.lib.core.MProperties;
import de.mhus.lib.core.MString;
import de.mhus.lib.core.MSystem;
import de.mhus.lib.core.MSystem.ScriptResult;
import de.mhus.lib.core.config.IConfig;
import de.mhus.lib.errors.MException;

/**
 * This implementation will use the kubectl command cp and exec ls / mkdir to control the container.
 * It will not delete files if removed locally.
 * 
 * @author mikehummel
 *
 */
public class WatchSimple extends Watch {

    long lastUpdated = System.currentTimeMillis();
    private File lastUpdatedFile;
    
    public WatchSimple(Job job, IConfig config) throws MException {
        super(job, config);
        lastUpdatedFile = new File(job.getConfigFile().getParent(), MFile.getFileNameOnly( job.getConfigFile().getName() ) + ".kpush" );
        if (job.getKPush().getArguments().contains("r"))
            lastUpdated = 0;
        else
            loadLastUpdated();
    }

    @Override
    public void init() {
                
        fileCnt = 0;
        long updateTime = System.currentTimeMillis();
        
        if (job.getConfig().getBoolean("ignoreInit", true)) {
            forEachSourceFile( (f,n) -> {
                if (f.lastModified() > lastUpdated) {
                    fileCnt++; 
                }
            });
            forEachSourceFile( (f,n) -> {
                if (f.lastModified() > lastUpdated) {
                    fileCnt--; 
                    log().i("Init",name,fileCnt,n);
                    try {
                        List<String> cmd = kubectl();
                        cmd.add("exec");
                        cmd.add(job.getPod());
                        cmd.add("--");
                        cmd.add("ls");
                        cmd.add("-l");
                        cmd.add(target + n);
                        ScriptResult res = MSystem.execute(cmd.toArray(M.EMPTY_STRING_ARRAY));
    
                        log().d( res );
    //                    if (res.getError().contains("No such file or directory")) {
                        if (res.getRc() != 0) {
                            log().i("copy",n);
                            pushToK8s(f, n);
                        }
                        
                    } catch (IOException e) {
                        // will fail if directory not exists
                        
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            });
            lastUpdated = updateTime;
        }
    }
    
    @Override
    public void push() {
        fileCnt = 0;
        long updateTime = System.currentTimeMillis();
        forEachSourceFile( (f,n) -> {
            if (f.lastModified() > lastUpdated) {
                fileCnt++; 
            }
        });
        forEachSourceFile( (f,n) -> {
            if (f.lastModified() > lastUpdated) {
                fileCnt--; 
                log().i("Update",name,fileCnt,n);
                pushToK8s(f, n);
            }
        });
        lastUpdated = updateTime;
        saveLastUpdated();
    }

    private void loadLastUpdated() {
        if (!job.getConfig().getBoolean("rememberLastUpdated", true)) return;
        if (lastUpdatedFile.exists() && lastUpdatedFile.isFile()) {
            try {
                log().i("load lastUpdated from",lastUpdatedFile);
                MProperties p = MProperties.load(lastUpdatedFile);
                lastUpdated = p.getLong("lastUpdated", lastUpdated);
                log().i("last update",MDate.toIsoDateTime(lastUpdated));
            } catch (Throwable t) {
                log().w(t);
            }
        }
    }
    
    private void saveLastUpdated() {
        if (!job.getConfig().getBoolean("rememberLastUpdated", true)) return;
        try {
            log().i("save lastUpdated to",lastUpdatedFile,MDate.toIsoDateTime(lastUpdated));
            MProperties p = new MProperties();
            p.setLong("lastUpdated", lastUpdated);
            p.save(lastUpdatedFile);
        } catch (Throwable t) {
            log().w(t);
        }
    }

    @Override
    public void pushAll() {
        fileCnt = 0;
        long updateTime = System.currentTimeMillis();
        forEachSourceFile( (f,n) -> {
            fileCnt++; 
        });
        forEachSourceFile( (f,n) -> {
            fileCnt--; 
            log().i("Update",name,fileCnt,n);
            pushToK8s(f,n);
        });
        lastUpdated = updateTime;
    }

    private void pushToK8s(File f, String n) {
        try {
            List<String> cmd = kubectl();
            cmd.add("cp");
            cmd.add(f.getAbsolutePath());
            cmd.add(job.getPod() + ":" + target + n);
            ScriptResult res = MSystem.execute(cmd.toArray(M.EMPTY_STRING_ARRAY));
            
            log().d(res);
//                if (res.getError().contains("No such file or directory")) {
            if (res.getRc() != 0) {
                String dir = MString.beforeLastIndex(n, '/');
                log().i("mkdir",dir);
                ScriptResult res3 = MSystem.execute(
                        "/usr/local/bin/kubectl",
                        "--namespace",job.getNamespace(), 
                        "-c", job.getContainer(), 
                        "exec", job.getPod(), "--", "mkdir", "-p", target + dir);
                log().d( res3 );
                if (res3.getRc() != 0) {
                    log().e("can't create directory",target,dir);
                    return;
                }
                //create
                ScriptResult res4 = MSystem.execute(
                        "/usr/local/bin/kubectl",
                        "--namespace",job.getNamespace(), 
                        "-c", job.getContainer(), 
                        "cp", f.getAbsolutePath(),job.getPod() + ":" + target + n);
                log().d( res4 );
                if (res4.getRc() != 0) {
                    log().e("can't create file",target,n);
                    return;
                }
                
            }
            
        } catch (IOException e) {
            // will fail if directory not exists
            
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
     }

    private List<String> kubectl() {
        LinkedList<String> cmd = new LinkedList<>();
        cmd.add( job.getConfig().getString("kubectl", "kubectl"));
        if (job.getNamespace() != null) {
            cmd.add("--namespace");
            cmd.add(job.getNamespace()); 
        }
        if (job.getContainer() != null) {
            cmd.add("-c");
            cmd.add(job.getContainer());
        }
        return cmd;
    }
    
}
