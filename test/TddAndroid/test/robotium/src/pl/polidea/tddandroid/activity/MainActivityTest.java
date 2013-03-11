package pl.polidea.tddandroid.activity;

import android.test.ActivityInstrumentationTestCase2;
import com.jayway.android.robotium.solo.Solo;
import junit.framework.Assert;

/**
 * This is a simple framework for a test of an Application.  See
 * {@link android.test.ApplicationTestCase ApplicationTestCase} for more information on
 * how to write and extend Application tests.
 * <p/>
 * To run this test, you can type:
 * adb shell am instrument -w \
 * -e class pl.polidea.tddandroid.activity.MainActivityTest \
 * pl.polidea.tddandroid.tests/android.test.InstrumentationTestRunner
 */
public class MainActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {


    private Solo solo;

    public MainActivityTest() {
        super(MainActivity.class);
    }

    public void setUp() throws Exception {
        solo = new Solo(getInstrumentation(), getActivity());
    }

    public void testHelloText() throws Exception {
        Assert.assertTrue(solo.searchText("Hello Szlif!"));
        Assert.assertTrue(solo.searchText("I have 8 MB"));
    }

    public void testClickingButton() throws Exception {
        solo.clickOnButton("Button2");

        // Can't access activity views
        solo.clickOnButton("Dismiss");

        Assert.assertTrue(solo.searchText("Clicked !"));
    }

    public void testDialog(){
        solo.clickOnButton("Button2");

        Assert.assertTrue(solo.searchText("Ok"));
        Assert.assertTrue(solo.searchText("Dismiss"));
        Assert.assertTrue(solo.searchText("Cancel"));
        Assert.assertTrue(solo.searchText("title"));
        Assert.assertTrue(solo.searchText("Dialog Content"));
    }

    public void testGoingToTaskActivity(){
        solo.clickOnButton("Load Imagelll");

        solo.waitForActivity("TaskActivity");

        solo.goBack();

        Assert.assertTrue(solo.getCurrentActivity() instanceof MainActivity);

    }


    @Override
    public void tearDown() throws Exception {
        solo.finishOpenedActivities();
    }

}
