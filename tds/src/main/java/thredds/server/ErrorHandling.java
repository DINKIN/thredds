/* Copyright */
package thredds.server;


import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.FileNotFoundException;

/**
 * Describe
 *
 * @author caron
 * @since 4/15/2015
 */
@ControllerAdvice
public class ErrorHandling {
  private static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ErrorHandling.class);

  @ExceptionHandler(FileNotFoundException.class)
  public ResponseEntity<String> handle(FileNotFoundException ex) {
    HttpHeaders responseHeaders = new HttpHeaders();
    responseHeaders.setContentType(MediaType.TEXT_PLAIN);
    return new ResponseEntity<>("FileNotFoundException : " + ex.getMessage(), responseHeaders, HttpStatus.NOT_FOUND);
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ResponseEntity<String> handle(UnsupportedOperationException ex) {
    HttpHeaders responseHeaders = new HttpHeaders();
    responseHeaders.setContentType(MediaType.TEXT_PLAIN);
    return new ResponseEntity<>("UnsupportedOperationException exception handled : " + ex.getMessage(), responseHeaders, HttpStatus.BAD_REQUEST);
  }

  // LOOK this could be a problem
  @ExceptionHandler(Throwable.class)
  public ResponseEntity<String> handle(Throwable ex) throws Throwable {
    // If the exception is annotated with @ResponseStatus rethrow it and let
    // the framework handle it - like the OrderNotFoundException example
    // at the start of this post.
    // AnnotationUtils is a Spring Framework utility class.
    // see https://spring.io/blog/2013/11/01/exception-handling-in-spring-mvc
    if (AnnotationUtils.findAnnotation(ex.getClass(), ResponseStatus.class) != null)
      throw ex;


    //  ex.printStackTrace();
    logger.error("uncaught exception", ex);

    HttpHeaders responseHeaders = new HttpHeaders();
    responseHeaders.setContentType(MediaType.TEXT_PLAIN);
    return new ResponseEntity<>("Throwable exception handled : " + ex.getMessage(), responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
  }


  /*
  see http://www.mytechnotes.biz/2012/08/spring-mvc-with-annotations-example.html


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.mvc.annotation.AnnotationMethodHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;

import java.util.ArrayList;
import java.util.List;
    @Bean
    HandlerExceptionResolverComposite getHandlerExceptionResolverComposite() {

      HandlerExceptionResolverComposite result = new HandlerExceptionResolverComposite();

      List<HandlerExceptionResolver> l = new ArrayList<>();

        l.add(new AnnotationMethodHandlerExceptionResolver());
        l.add(new ResponseStatusExceptionResolver());
        l.add(getSimpleMappingExceptionResolver());
        l.add(new DefaultHandlerExceptionResolver());

      result.setExceptionResolvers(l);

      return result;
    }      */

}