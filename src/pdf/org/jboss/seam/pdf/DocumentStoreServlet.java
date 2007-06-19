package org.jboss.seam.pdf;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jboss.seam.contexts.ContextualHttpServletRequest;
import org.jboss.seam.web.Parameters;

public class DocumentStoreServlet 
    extends HttpServlet 
{
    private static final long serialVersionUID = 5196002741557182072L;

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) 
        throws ServletException, IOException 
    {
        new ContextualHttpServletRequest( request, getServletContext() )
        {
            @Override
            public void process() throws ServletException, IOException
            {
               doWork(request, response);
            }
        }.run();
    
    }    
    
    
    private static void doWork(HttpServletRequest request, HttpServletResponse response) 
        throws IOException 
    {
        Parameters params = Parameters.instance();
        String contentId = (String) params.convertMultiValueRequestParameter(params.getRequestParameters(), "docId", String.class);        
                
        DocumentStore store = DocumentStore.instance();
        
        if ( store.idIsValid(contentId) ) 
        {
            DocumentData documentData = store.getDocumentData(contentId);
            
            byte[] data = documentData.getData();       

            response.setContentType( documentData.getDocType().getMimeType() );
            response.setHeader("Content-Disposition", 
                    "inline; filename=\"" + documentData.getFileName() + "\"");

            if (data != null) 
            {
                response.getOutputStream().write(data);
            }
        } 
        else 
        {
             String error = store.getErrorPage();             
             if (error != null) 
             {      
                 if (error.startsWith("/")) 
                 {
                     error = request.getContextPath() + error;
                 }
                 response.sendRedirect(error);
             } 
             else 
             {
                 response.sendError(404);
             }
        }
    }
}
