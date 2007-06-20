//$Id$
package org.jboss.seam.init;

import java.io.File;

import org.jboss.seam.deployment.ComponentScanner;

/**
 * Abstract the redeployable initialization mechanism
 * to prevent hard dependency between Seam and the
 * scripting language infrastructure
 *
 * @author Emmanuel Bernard
 */
interface RedeployableStrategy
{
   /*
    * Mandatory constructor
    *
    * @param resource url containing the redeployable files
    */
   //RedeployableInitialization(URL resource);


   public ClassLoader getClassLoader();

   public File[] getPaths();

   public ComponentScanner getScanner();

   public boolean isFromHotDeployClassLoader(Class componentClass);
   
}
