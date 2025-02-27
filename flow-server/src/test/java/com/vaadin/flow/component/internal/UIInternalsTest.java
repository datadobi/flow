package com.vaadin.flow.component.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.PushConfiguration;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.di.DefaultInstantiator;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.ParentLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.shared.communication.PushMode;
import com.vaadin.flow.shared.ui.Transport;
import com.vaadin.tests.util.AlwaysLockedVaadinSession;

public class UIInternalsTest {

    @Mock
    UI ui;
    @Mock
    VaadinService vaadinService;

    UIInternals internals;

    @Route
    @Push
    @Tag(Tag.DIV)
    public static class RouteTarget extends Component implements RouterLayout {

    }

    @Route(value = "foo", layout = RouteTarget.class)
    @Tag(Tag.DIV)
    public static class RouteTarget1 extends Component {

    }

    @Tag(Tag.DIV)
    static class MainLayout extends Component implements RouterLayout {
        static String ID = "main-layout-id";

        public MainLayout() {
            setId(ID);
        }
    }

    @Tag(Tag.DIV)
    @ParentLayout(MainLayout.class)
    static class SubLayout extends Component implements RouterLayout {
        static String ID = "sub-layout-id";

        public SubLayout() {
            setId(ID);
        }
    }

    @Tag(Tag.DIV)
    @Route(value = "child", layout = SubLayout.class)
    static class FirstView extends Component {
        static String ID = "child-view-id";

        public FirstView() {
            setId(ID);
        }
    }

    @Tag(Tag.DIV)
    static class AnotherLayout extends Component implements RouterLayout {
        static String ID = "another-layout-id";

        public AnotherLayout() {
            setId(ID);
        }
    }

    @Tag(Tag.DIV)
    @Route(value = "another", layout = MainLayout.class)
    static class AnotherView extends Component {
        static String ID = "another-view-id";

        public AnotherView() {
            setId(ID);
        }
    }

    @Before
    public void init() {
        MockitoAnnotations.initMocks(this);

        Mockito.when(ui.getUI()).thenReturn(Optional.of(ui));
        Element body = new Element("body");
        Mockito.when(ui.getElement()).thenReturn(body);

        internals = new UIInternals(ui);
        AlwaysLockedVaadinSession session = new AlwaysLockedVaadinSession(
                vaadinService);
        VaadinContext context = Mockito.mock(VaadinContext.class);
        Mockito.when(vaadinService.getContext()).thenReturn(context);
        Mockito.when(vaadinService.getInstantiator())
                .thenReturn(new DefaultInstantiator(vaadinService));
        internals.setSession(session);
        Mockito.when(ui.getSession()).thenReturn(session);
    }

    @Test
    public void heartbeatTimestampSet_heartbeatListenersAreCalled() {
        List<Long> heartbeats = new ArrayList<>();
        Registration registration = internals.addHeartbeatListener(
                event -> heartbeats.add(event.getHeartbeatTime()));

        internals.setLastHeartbeatTimestamp(System.currentTimeMillis());

        Assert.assertEquals("Heartbeat listener should have fired", 1,
                heartbeats.size());

        registration.remove();

        internals.setLastHeartbeatTimestamp(System.currentTimeMillis());

        Assert.assertEquals(
                "Heartbeat listener should been removed and no new event recorded",
                1, heartbeats.size());
    }

    @Test
    public void heartbeatListenerRemovedFromHeartbeatEvent_noExplosion() {
        AtomicReference<Registration> reference = new AtomicReference<>();
        AtomicInteger runCount = new AtomicInteger();

        Registration registration = internals.addHeartbeatListener(event -> {
            runCount.incrementAndGet();
            reference.get().remove();
        });
        reference.set(registration);

        internals.setLastHeartbeatTimestamp(System.currentTimeMillis());
        Assert.assertEquals("Listener should have been run once", 1,
                runCount.get());

        internals.setLastHeartbeatTimestamp(System.currentTimeMillis());
        Assert.assertEquals(
                "Listener should not have been run again since it was removed",
                1, runCount.get());
    }

    @Test
    public void showRouteTarget_clientSideBootstrap() {
        PushConfiguration pushConfig = setUpInitialPush();

        internals.showRouteTarget(Mockito.mock(Location.class),
                new RouteTarget(), Collections.emptyList());

        Mockito.verify(pushConfig, Mockito.never()).setPushMode(Mockito.any());
    }

    @Test
    public void showRouteTarget_navigateToAnotherViewWithinSameLayoutHierarchy_detachedRouterLayoutChildrenRemoved() {
        MainLayout mainLayout = new MainLayout();
        SubLayout subLayout = new SubLayout();
        FirstView firstView = new FirstView();
        AnotherView anotherView = new AnotherView();

        List<RouterLayout> oldLayouts = Arrays.asList(subLayout, mainLayout);
        List<RouterLayout> newLayouts = Collections.singletonList(mainLayout);

        Location location = Mockito.mock(Location.class);
        setUpInitialPush();

        internals.showRouteTarget(location, firstView, oldLayouts);
        List<HasElement> activeRouterTargetsChain = internals
                .getActiveRouterTargetsChain();

        // Initial router layouts hierarchy is checked here in order to be
        // sure the sub layout and it's child view is in place BEFORE
        // navigation and old content cleanup
        Assert.assertArrayEquals("Unexpected initial router targets chain",
                new HasElement[] { firstView, subLayout, mainLayout },
                activeRouterTargetsChain.toArray());

        Assert.assertEquals(
                "Expected one child element for main layout before navigation",
                1, mainLayout.getElement().getChildren().count());
        @SuppressWarnings("OptionalGetWithoutIsPresent")
        Element subLayoutElement = mainLayout.getElement().getChildren()
                .findFirst().get();
        Assert.assertEquals("Unexpected sub layout element", SubLayout.ID,
                subLayoutElement.getAttribute("id"));
        Assert.assertEquals(
                "Expected one child element for sub layout before navigation",
                1, subLayoutElement.getChildren().count());
        @SuppressWarnings("OptionalGetWithoutIsPresent")
        Element firstViewElement = subLayoutElement.getChildren().findFirst()
                .get();
        Assert.assertEquals("Unexpected first view element", FirstView.ID,
                firstViewElement.getAttribute("id"));

        // Trigger navigation
        internals.showRouteTarget(location, anotherView, newLayouts);
        activeRouterTargetsChain = internals.getActiveRouterTargetsChain();
        Assert.assertArrayEquals(
                "Unexpected router targets chain after navigation",
                new HasElement[] { anotherView, mainLayout },
                activeRouterTargetsChain.toArray());

        // Check that the old content (sub layout) is detached and it's
        // children are also detached
        Assert.assertEquals(
                "Expected one child element for main layout after navigation",
                1, mainLayout.getElement().getChildren().count());
        @SuppressWarnings("OptionalGetWithoutIsPresent")
        Element anotherViewElement = mainLayout.getElement().getChildren()
                .findFirst().get();
        Assert.assertEquals("Unexpected another view element", AnotherView.ID,
                anotherViewElement.getAttribute("id"));
        Assert.assertEquals(
                "Expected no child elements for sub layout after navigation", 0,
                subLayout.getElement().getChildren().count());
    }

    @Test
    public void showRouteTarget_navigateToAnotherLayoutHierarchy_detachedLayoutHierarchyChildrenRemoved() {
        MainLayout mainLayout = new MainLayout();
        SubLayout subLayout = new SubLayout();
        FirstView firstView = new FirstView();
        AnotherLayout anotherLayout = new AnotherLayout();
        AnotherView anotherView = new AnotherView();

        List<RouterLayout> oldLayouts = Arrays.asList(subLayout, mainLayout);
        List<RouterLayout> newLayouts = Collections
                .singletonList(anotherLayout);

        Location location = Mockito.mock(Location.class);
        setUpInitialPush();

        // Initial navigation
        internals.showRouteTarget(location, firstView, oldLayouts);
        // Navigate to another view outside of the initial router hierarchy
        internals.showRouteTarget(location, anotherView, newLayouts);
        List<HasElement> activeRouterTargetsChain = internals
                .getActiveRouterTargetsChain();
        Assert.assertArrayEquals(
                "Unexpected router targets chain after navigation",
                new HasElement[] { anotherView, anotherLayout },
                activeRouterTargetsChain.toArray());

        // Check that both main layout, sub layout and it's child view are
        // detached
        Assert.assertEquals(
                "Expected no child elements for main layout after navigation",
                0, mainLayout.getElement().getChildren().count());
        Assert.assertEquals(
                "Expected no child elements for sub layout after navigation", 0,
                subLayout.getElement().getChildren().count());
    }

    private PushConfiguration setUpInitialPush() {
        DeploymentConfiguration config = Mockito
                .mock(DeploymentConfiguration.class);
        Mockito.when(vaadinService.getDeploymentConfiguration())
                .thenReturn(config);

        PushConfiguration pushConfig = Mockito.mock(PushConfiguration.class);
        Mockito.when(ui.getPushConfiguration()).thenReturn(pushConfig);

        Mockito.when(config.getPushMode()).thenReturn(PushMode.DISABLED);
        return pushConfig;
    }

}
