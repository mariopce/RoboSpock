package pl.polidea.tddandroid.activity;

import android.test.ActivityInstrumentationTestCase2;
import com.jayway.android.robotium.solo.Solo;
import junit.framework.Assert;

public class TaskActivityTest extends ActivityInstrumentationTestCase2<TaskActivity> {


    private Solo solo;

    public TaskActivityTest() {
        super(TaskActivity.class);
    }

    public void setUp() throws Exception {
        solo = new Solo(getInstrumentation(), getActivity());
    }

    public void testHelloText() throws Exception {
        Assert.assertTrue(solo.searchText("Hi! I'm text from ext :)"));
    }

    public void testImageLoading(){
        solo.clickOnButton("Load Imagelll");

        Assert.assertNotNull(solo.getImage(0).getDrawable());
    }


    @Override
    public void tearDown() throws Exception {
        solo.finishOpenedActivities();
    }

}