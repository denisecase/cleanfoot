package threadchecker;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

import java.io.File;
import java.util.Arrays;

public class TCPlugin implements Plugin
{
    private File tagsDump = new File("found-tags.txt");
    
    public TCPlugin()
    {
        if (tagsDump.exists())
            tagsDump.delete();
    }
    
    @Override
    public String getName()
    {
        return "threadchecker.TCPlugin";
    }

    @Override
    public void init(JavacTask task, String... ignorePackages) 
    {
        task.setTaskListener(new TCTaskListener(task, ignorePackages));
    }
    
    private class TCTaskListener implements TaskListener
    {
        private TCScanner scanner;
        
        public TCTaskListener(JavacTask task, String[] ignorePackages)
        {
            try
            {
                this.scanner = new TCScanner(task, Arrays.asList(ignorePackages));
            }
            catch (NoSuchMethodException e)
            {
                e.printStackTrace();
            }
        }
        
        @Override
        public void finished(TaskEvent evt)
        {
            if (evt.getKind() == TaskEvent.Kind.ANALYZE)
            {
                scanner.scan(evt.getCompilationUnit(), null);
                // Uncomment to get tags dump:
                /*
                try
                {
                    LocatedTag.dumpTagList(tagsDump);
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
                */
            }
        }

        @Override
        public void started(TaskEvent arg0)
        {
            
        }
        
    }
}
