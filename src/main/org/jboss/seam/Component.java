/*
 * JBoss, Home of Professional Open Source
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jboss.seam;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.ejb.Local;
import javax.ejb.Remove;
import javax.faces.el.EvaluationException;
import javax.naming.InitialContext;

import org.hibernate.validator.ClassValidator;
import org.jboss.logging.Logger;
import org.jboss.seam.annotations.Around;
import org.jboss.seam.annotations.Conversational;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.Destroy;
import org.jboss.seam.annotations.IfInvalid;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.JndiName;
import org.jboss.seam.annotations.Out;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.Unwrap;
import org.jboss.seam.annotations.Within;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.interceptors.BijectionInterceptor;
import org.jboss.seam.interceptors.BusinessProcessInterceptor;
import org.jboss.seam.interceptors.ConversationInterceptor;
import org.jboss.seam.interceptors.Interceptor;
import org.jboss.seam.interceptors.OutcomeInterceptor;
import org.jboss.seam.interceptors.RemoveInterceptor;
import org.jboss.seam.interceptors.ValidationInterceptor;
import org.jboss.seam.util.Reflections;
import org.jboss.seam.util.Sorter;
import org.jboss.seam.util.Strings;

/**
 * A Seam component is any POJO managed by Seam.
 * A POJO is recognized as a Seam component if it is using the org.jboss.seam.annotations.Name annotation
 * 
 * @author <a href="mailto:theute@jboss.org">Thomas Heute</a>
 * @author Gavin King
 * @version $Revision$
 */
@Scope(ScopeType.APPLICATION)
public class Component
{
   private static final Logger log = Logger.getLogger(Component.class);

   private ComponentType type;
   private String name;
   private ScopeType scope;
   private Class<?> beanClass;
   private String jndiName;
   
   private Method destroyMethod;
   private Method createMethod;
   private Method unwrapMethod;
   private Set<Method> removeMethods = new HashSet<Method>();
   private Set<Method> validateMethods = new HashSet<Method>();
   private Set<Method> inMethods = new HashSet<Method>();
   private Set<Field> inFields = new HashSet<Field>();
   private Set<Method> outMethods = new HashSet<Method>();
   private Set<Field> outFields = new HashSet<Field>();
   
   private ClassValidator validator;
   
   private List<Interceptor> interceptors = new ArrayList<Interceptor>();
   
   private Set<Class> localInterfaces;
   
   private String ifNoConversationOutcome;

   public Component(Class<?> clazz)
   {
      this( clazz, Seam.getComponentName(clazz) );
   }
   
   public Component(Class<?> clazz, String componentName)
   {
      beanClass = clazz;
      name = componentName;
      scope = Seam.getComponentScope(beanClass);
      type = Seam.getComponentType(beanClass);
      
      if ( beanClass.isAnnotationPresent(JndiName.class) )
      {
         jndiName = beanClass.getAnnotation(JndiName.class).value();
      }
      else
      {
         jndiName = name;
      }
      
      log.info("Component: " + getName() + ", scope: " + getScope() + ", type: " + getType());

      if ( beanClass.isAnnotationPresent(Conversational.class) )
      {
         ifNoConversationOutcome = beanClass.getAnnotation(Conversational.class).ifNotBegunOutcome();
      }

      for (;clazz!=Object.class; clazz = clazz.getSuperclass())
      {
      
         for (Method method: clazz.getDeclaredMethods()) //TODO: inheritance!
         {
            if ( method.isAnnotationPresent(IfInvalid.class) )
            {
               validateMethods.add(method);  
            }
            if ( method.isAnnotationPresent(Remove.class) )
            {
               removeMethods.add(method);  
            }
            if ( method.isAnnotationPresent(Destroy.class) )
            {
               destroyMethod = method;
            }
            if ( method.isAnnotationPresent(Create.class) )
            {
               createMethod = method;
            }
            if ( method.isAnnotationPresent(In.class) )
            {
               inMethods.add(method);
            }
            if ( method.isAnnotationPresent(Out.class) )
            {
               outMethods.add(method);
            }
            if ( method.isAnnotationPresent(Unwrap.class) )
            {
               unwrapMethod = method;
            }
            if ( !method.isAccessible() )
            {
               method.setAccessible(true);
            }
         }
         
         for (Field field: clazz.getDeclaredFields()) //TODO: inheritance!
         {
            if ( field.isAnnotationPresent(In.class) )
            {
               inFields.add(field);
            }
            if ( field.isAnnotationPresent(Out.class) )
            {
               outFields.add(field);
            }
            if ( !field.isAccessible() )
            {
               field.setAccessible(true);
            }
         }
         
      }
         
      validator = new ClassValidator(beanClass);
      
      localInterfaces = getLocalInterfaces(beanClass);
      
      initDefaultInterceptors();
      
      for (Annotation annotation: beanClass.getAnnotations())
      {
         if ( annotation.annotationType().isAnnotationPresent(javax.ejb.Interceptor.class) )
         {
            interceptors.add( new Interceptor(annotation, this) );
         }
      }
      
      new Sorter<Interceptor>() {
         protected boolean isOrderViolated(Interceptor outside, Interceptor inside)
         {
            Class<?> insideClass = inside.getUserInterceptor().getClass();
            Class<?> outsideClass = outside.getUserInterceptor().getClass();
            Around around = insideClass.getAnnotation(Around.class);
            Within within = outsideClass.getAnnotation(Within.class);
            return ( around!=null && Arrays.asList( around.value() ).contains( outsideClass ) ) ||
                  ( within!=null && Arrays.asList( within.value() ).contains( insideClass ) );
         }
      }.sort(interceptors);
      
      log.info("interceptor stack: " + interceptors);
      
   }

   private void initDefaultInterceptors()
   {
      interceptors.add( new Interceptor( new OutcomeInterceptor(), this ) );
      interceptors.add( new Interceptor( new RemoveInterceptor(), this ) );
      interceptors.add( new Interceptor( new BusinessProcessInterceptor(), this ) );
      interceptors.add( new Interceptor( new ConversationInterceptor(), this ) );
      interceptors.add( new Interceptor( new BijectionInterceptor(), this ) );
      interceptors.add( new Interceptor( new ValidationInterceptor(), this ) );
   }

   public Class getBeanClass()
   {
      return beanClass;
   }

   public String getName()
   {
      return name;
   }
   
   public ComponentType getType()
   {
      return type;
   }

   public ScopeType getScope()
   {
      return scope;
   }
   
   public ClassValidator getValidator() 
   {
      return validator;
   }
   
   public List<Interceptor> getInterceptors()
   {
      return interceptors;
   }
   
   public Method getDestroyMethod()
   {
      return destroyMethod;
   }

   public Set<Method> getRemoveMethods()
   {
      return removeMethods;
   }
   
   public Set<Method> getValidateMethods()
   {
      return validateMethods;
   }
   
   public boolean hasDestroyMethod() 
   {
      return destroyMethod!=null;
   }

   public boolean hasCreateMethod() 
   {
      return createMethod!=null;
   }

   public Method getCreateMethod()
   {
      return createMethod;
   }

   public boolean hasUnwrapMethod() 
   {
      return unwrapMethod!=null;
   }

   public Method getUnwrapMethod()
   {
      return unwrapMethod;
   }

   public Set<Field> getOutFields()
   {
      return outFields;
   }

   public Set<Method> getOutMethods()
   {
      return outMethods;
   }

   public Set<Method> getInMethods()
   {
      return inMethods;
   }

   public Set<Field> getInFields()
   {
      return inFields;
   }

   public Object newInstance()
   {
      try 
      {
         switch(type)
         {
            case JAVA_BEAN: 
            case ENTITY_BEAN:
               Object bean = beanClass.newInstance();
               inject(bean);
               return bean;
            case STATELESS_SESSION_BEAN : 
            case STATEFUL_SESSION_BEAN :
               return new InitialContext().lookup(jndiName);
            default:
               throw new IllegalStateException();
         }
      }
      catch (Exception e)
      {
         throw new InstantiationException("Could not instantiate component", e);
      }
   }
   
   public void inject(Object bean)
   {
      injectMethods(bean);
      injectFields(bean);
   }

   public void outject(Object bean)
   {
      outjectMethods(bean);
      outjectFields(bean);
   }

   public void injectMethods(Object bean)
   {
      for (Method method : getInMethods())
      {
         In in = method.getAnnotation(In.class);
         if (in != null)
         {
            String name = toName(in.value(), method);
            inject( bean, method, name, getInstanceToInject(in, name, bean) );
         }
      }
   }

   private void injectFields(Object bean)
   {
      for (Field field : getInFields())
      {
         In in = field.getAnnotation(In.class);
         if (in != null)
         {
            String name = toName(in.value(), field);
            inject( bean, field, name, getInstanceToInject(in, name, bean) );
         }
      }
   }

   private void outjectFields(Object bean)
   {
      for (Field field : getOutFields())
      {
         Out out = field.getAnnotation(Out.class);
         if (out != null)
         {
            setOutjectedValue( out, toName(out.value(), field), outject(bean, field) );
         }
      }
   }

   private void outjectMethods(Object bean)
   {
      for (Method method : getOutMethods())
      {
         Out out = method.getAnnotation(Out.class);
         if (out != null)
         {
            setOutjectedValue( out, toName(out.value(), method), outject(bean, method) );
         }
      }
   }

   private void setOutjectedValue(Out out, String name, Object value)
   {
      if (value==null && out.required())
      {
         throw new RequiredException("Out attribute requires value for component: " + name);
      }
      else 
      {
         Component component = Component.forName(name);
         if (value!=null && component!=null)
         {
            if ( !component.isInstance(value) )
            {
               throw new IllegalArgumentException("attempted to bind an Out attribute of the wrong type to: " + name);
            }
         }
         ScopeType scope = component==null ? 
               ScopeType.CONVERSATION : component.getScope();
         scope.getContext().set(name, value);
      }
   }
   
   public boolean isInstance(Object bean)
   {
      switch(type)
      {
         case JAVA_BEAN:
         case ENTITY_BEAN:
            return beanClass.isInstance(bean);
         default:
            Class clazz = bean.getClass();
            for (Class intfc: localInterfaces)
            {
               if (intfc.isAssignableFrom(clazz))
               {
                  return true;
               }
            }
            return false;
      }
   }
   
   private static Set<Class> getLocalInterfaces(Class clazz)
   {
      Set<Class> result = new HashSet<Class>();
      for (Class iface: clazz.getInterfaces())
      {
         if ( iface.isAnnotationPresent(Local.class))
         {
            result.add(iface);
         }
      }
      return result;
   }

   private Object outject(Object bean, Field field)
   {
      try {
         return field.get(bean);
      }
      catch (Exception e)
      {
         throw new IllegalArgumentException("could not outject: " + name, e);         
      }
   }

   private Object outject(Object bean, Method method)
   {
      try {
         return Reflections.invoke(method, bean);
      }
      catch (Exception e)
      {
         throw new IllegalArgumentException("could not outject: " + name, e);         
      }
   }

   private void inject(Object bean, Method method, String name, Object value)
   {  
      try
      {
         Reflections.invoke(method, bean, value );
      } 
      catch (Exception e)
      {
         throw new IllegalArgumentException("could not inject: " + name, e);
      }
   }

   private void inject(Object bean, Field field, String name, Object value)
   {  
      try
      {
         field.set(bean, value);
      } 
      catch (Exception e)
      {
         throw new IllegalArgumentException("could not inject: " + name, e);
      }
   }
   
   public boolean isConversational()
   {
      return ifNoConversationOutcome!=null;
   }
   
   public String getNoConversationOutcome()
   {
      return ifNoConversationOutcome;
   }
   
   public static Component forName(String name)
   {
      return (Component) getInstance( name+".component", false );
   }

   public static Object getInstance(String name, boolean create) throws EvaluationException
   {
      Object result = Contexts.lookupInStatefulContexts(name);
      if (result == null && create)
      {
          result = newInstance(name);
      }
      if (result!=null) 
      {
         Component component = Component.forName(name);
         if (component!=null)
         {
            if ( !component.isInstance(result) )
            {
               throw new IllegalArgumentException("value found for In attribute has the wrong type: " + name);
            }
         }
         result = unwrap( component, result );
         if ( log.isTraceEnabled() ) 
         {
            log.trace( Strings.toString(result) );
         }
      }
      return result;
   }

   public static Object newInstance(String name)
   {
      Component component = Component.forName(name);
      if (component == null)
      {
         log.info("seam component not found: " + name);
         return null; //needed when this method is called by JSF
      }
      else
      {
         log.info("instantiating seam component: " + name);
         Object instance = component.newInstance();
         if (component.getType()!=ComponentType.STATELESS_SESSION_BEAN)
         {
            callCreateMethod(component, instance);
            component.getScope().getContext().set(name, instance);
         }
         return instance;
      }
   }

   private static void callCreateMethod(Component component, Object instance)
   {
      if (component.hasCreateMethod())
      {
         Method createMethod = component.getCreateMethod();
         Class[] paramTypes = createMethod.getParameterTypes();
         Object param = paramTypes.length==0 ? null : component;
         String createMethodName = createMethod.getName();
         try 
         {
            Method method = instance.getClass().getMethod(createMethodName, paramTypes);
            Reflections.invokeAndWrap( method, instance, param );
         }
         catch (NoSuchMethodException e)
         {
            throw new IllegalArgumentException("create method not found", e);
         }
      }
   }

   private static Object unwrap(Component component, Object instance)
   {
      if (component!=null && component.hasUnwrapMethod())
      {
         instance = Reflections.invokeAndWrap(component.getUnwrapMethod(), instance);
      }
      return instance;
   }

   private static Object getInstanceToInject(In in, String name, Object bean)
   {
      Object result = getInstance(name, in.create());
      if (result==null && in.required())
      {
         throw new RequiredException("In attribute requires value for component: " + name);
      }
      else
      {
         return result;
      }
   }
   
   private static String toName(String name, Method method)
   {
      if (name==null || name.length() == 0)
      {
         name = method.getName().substring(3, 4).toLowerCase()
               + method.getName().substring(4);
      }
      return name;
   }

   private static String toName(String name, Field field)
   {
      if (name==null || name.length() == 0)
      {
         name = field.getName();
      }
      return name;
   }
   
}
