package io.hyperfoil.core.generators;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.kohsuke.MetaInfServices;

import io.hyperfoil.api.config.BenchmarkDefinitionException;
import io.hyperfoil.api.config.Locator;
import io.hyperfoil.api.config.Name;
import io.hyperfoil.api.processor.Transformer;
import io.hyperfoil.api.session.Access;
import io.hyperfoil.api.session.Action;
import io.hyperfoil.api.session.ResourceUtilizer;
import io.hyperfoil.api.session.Session;
import io.hyperfoil.core.builders.ServiceLoadedBuilderProvider;
import io.hyperfoil.core.data.DataFormat;
import io.hyperfoil.core.handlers.DefragTransformer;
import io.hyperfoil.core.session.SessionFactory;
import io.netty.buffer.ByteBuf;

public class ActionsTransformer implements Transformer, ResourceUtilizer {
   private final Access var;
   private final DataFormat format;
   private final Action[] actions;
   private final Pattern pattern;

   public ActionsTransformer(Access var, DataFormat format, Action[] actions, Pattern pattern) {
      this.var = var;
      this.format = format;
      this.actions = actions;
      this.pattern = pattern;
   }

   @Override
   public void transform(Session session, ByteBuf in, int offset, int length, boolean lastFragment, ByteBuf out) {
      assert lastFragment;
      var.setObject(session, format.convert(in, offset, length));
      for (Action action: actions) {
         action.run(session);
      }
      pattern.accept(session, out);
   }

   @Override
   public void reserve(Session session) {
      var.declareObject(session);
      ResourceUtilizer.reserve(session, (Object[]) actions);
   }

   /**
    * This transformer stores the (defragmented) input into a variable, using requested format.
    * After that it executes all the actions and fetches transformed value using the pattern.
    */
   @MetaInfServices(Transformer.Builder.class)
   @Name("actions")
   public static class Builder implements Transformer.Builder {
      private Locator locator;
      private String var;
      private DataFormat format = DataFormat.STRING;
      private String pattern;
      private List<Action.Builder> actions = new ArrayList<>();

      @Override
      public Builder setLocator(Locator locator) {
         this.locator = locator;
         for (Action.Builder a : actions) {
            a.setLocator(locator);
         }
         return this;
      }

      @Override
      public Builder copy(Locator locator) {
         return new Builder().setLocator(locator).var(var).format(format).pattern(pattern)
               .actions(actions.stream().map(a -> a.copy(locator)).collect(Collectors.toList()));
      }

      /**
       * Variable used as the intermediate storage for the data.
       *
       * @param var Variable name.
       * @return Self.
       */
      public Builder var(String var) {
         this.var = var;
         return this;
      }

      /**
       * Format into which should this transformer convert the buffers before storing. Default is <code>STRING</code>.
       *
       * @param format Data format.
       * @return Self.
       */
      public Builder format(DataFormat format) {
         this.format = format;
         return this;
      }

      /**
       * @param pattern Pattern to use when fetching the transformed value.
       * @return Self.
       */
      public Builder pattern(String pattern) {
         this.pattern = pattern;
         return this;
      }

      private Builder actions(List<Action.Builder> actions) {
         this.actions = actions;
         return this;
      }

      /**
       * @return Builder for creating the list of actions.
       */
      public ServiceLoadedBuilderProvider<Action.Builder> actions() {
         return new ServiceLoadedBuilderProvider<>(Action.Builder.class, locator, actions::add);
      }

      @Override
      public Transformer build(boolean fragmented) {
         if (var == null) {
            throw new BenchmarkDefinitionException("Missing variable name");
         } else if (actions.isEmpty()) {
            throw new BenchmarkDefinitionException("No actions; use `simple` processor instead.");
         }
         ActionsTransformer transformer = new ActionsTransformer(SessionFactory.access(var), format,
               actions.stream().map(Action.Builder::build).toArray(Action[]::new), new Pattern(pattern, false));
         return fragmented ? transformer : new DefragTransformer(transformer);
      }
   }
}